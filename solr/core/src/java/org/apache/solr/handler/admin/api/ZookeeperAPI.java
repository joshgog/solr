/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.handler.admin.api;

import static org.apache.solr.client.solrj.impl.BinaryResponseParser.BINARY_CONTENT_TYPE_V2;
import static org.apache.solr.common.params.CommonParams.OMIT_HEADER;
import static org.apache.solr.common.params.CommonParams.PATH;
import static org.apache.solr.common.params.CommonParams.WT;
import static org.apache.solr.security.PermissionNameProvider.Name.ZK_READ_PERM;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import org.apache.solr.api.JerseyResource;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkDynamicConfig;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.admin.ZookeeperInfoHandler.FilterType;
import org.apache.solr.handler.admin.ZookeeperInfoHandler.PageOfCollections;
import org.apache.solr.handler.admin.ZookeeperInfoHandler.PagedCollectionSupport;
import org.apache.solr.handler.admin.ZookeeperInfoHandler.ZKPrinter;
import org.apache.solr.handler.admin.ZookeeperStatusHandler;
import org.apache.solr.jersey.PermissionName;
import org.apache.solr.jersey.SolrJerseyResponse;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/cluster/zookeeper/")
public class ZookeeperAPI extends JerseyResource {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final CoreContainer coreContainer;
  private final SolrQueryRequest solrQueryRequest;
  private final SolrQueryResponse solrQueryResponse;
  private PagedCollectionSupport pagingSupport;

  @Inject
  public ZookeeperAPI(
      CoreContainer coreContainer,
      SolrQueryRequest solrQueryRequest,
      SolrQueryResponse solrQueryResponse) {
    this.coreContainer = coreContainer;
    this.solrQueryRequest = solrQueryRequest;
    this.solrQueryResponse = solrQueryResponse;
  }

  @GET
  @Path("/files")
  @Produces({"application/json", "application/xml", BINARY_CONTENT_TYPE_V2})
  @Operation(
      summary = "List Zookeeper files.",
      tags = {"zookeeperFiles"})
  @PermissionName(ZK_READ_PERM)
  public ZookeeperFilesResponse getFiles() throws Exception {
    final ZookeeperFilesResponse response = instantiateJerseyResponse(ZookeeperFilesResponse.class);
    final SolrParams params = solrQueryRequest.getParams();
    Map<String, String> map = new HashMap<>(1);
    map.put(WT, "raw");
    map.put(OMIT_HEADER, "true");
    synchronized (this) {
      if (pagingSupport == null) {
        pagingSupport = new PagedCollectionSupport();
        ZkController zkController = coreContainer.getZkController();
        if (zkController != null) {
          // get notified when the ZK session expires (so we can clear the cached collections and
          // rebuild)
          zkController.addOnReconnectListener(pagingSupport);
        }
      }
    }

    String path = params.get(PATH);

    if (params.get("addr") != null) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Illegal parameter \"addr\"");
    }

    boolean detail = false;

    String dumpS = params.get("dump");
    boolean dump = dumpS != null && dumpS.equals("true");

    int start = params.getInt("start", 0); // Note start ignored if rows not specified
    int rows = params.getInt("rows", -1);

    String filterType = params.get("filterType");
    if (filterType != null) {
      filterType = filterType.trim().toLowerCase(Locale.ROOT);
      if (filterType.length() == 0) filterType = null;
    }
    FilterType type = (filterType != null) ? FilterType.valueOf(filterType) : FilterType.none;

    String filter = (type != FilterType.none) ? params.get("filter") : null;
    if (filter != null) {
      filter = filter.trim();
      if (filter.length() == 0) filter = null;
    }

    ZKPrinter printer = new ZKPrinter(coreContainer.getZkController());
    printer.detail = detail;
    printer.dump = dump;
    boolean isGraphView = "graph".equals(params.get("view"));
    // There is no znode /clusterstate.json (removed in Solr 9), but we do as if there's one and
    // return collection listing. Need to change services.js if cleaning up here, collection list is
    // used from Admin UI Cloud - Graph
    boolean paginateCollections = (isGraphView && "/clusterstate.json".equals(path));
    printer.page = paginateCollections ? new PageOfCollections(start, rows, type, filter) : null;
    printer.pagingSupport = pagingSupport;

    try {
      if (paginateCollections) {
        // List collections and allow pagination, but no specific znode info like when looking at a
        // normal ZK path
        response.zookeeperFiles = printer.printPaginatedCollections();
      } else {
        response.zookeeperFiles = printer.print(path);
      }
    } finally {
      printer.close();
    }
    return response;
  }

  @GET
  @Path("/files/{path}")
  @Produces({"application/json", "application/xml", BINARY_CONTENT_TYPE_V2})
  @Operation(
      summary = "List Zookeeper file.",
      tags = {"zookeeperFile"})
  @PermissionName(ZK_READ_PERM)
  public ZookeeperFileResponse getFile() throws Exception {
    final ZookeeperFileResponse response = instantiateJerseyResponse(ZookeeperFileResponse.class);
    final SolrParams params = solrQueryRequest.getParams();
    Map<String, String> map = new HashMap<>(1);
    map.put(WT, "raw");
    map.put(OMIT_HEADER, "true");
    synchronized (this) {
      if (pagingSupport == null) {
        pagingSupport = new PagedCollectionSupport();
        ZkController zkController = coreContainer.getZkController();
        if (zkController != null) {
          // get notified when the ZK session expires (so we can clear the cached collections and
          // rebuild)
          zkController.addOnReconnectListener(pagingSupport);
        }
      }
    }

    String path = params.get(PATH);

    if (params.get("addr") != null) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Illegal parameter \"addr\"");
    }

    boolean detail = true;

    String dumpS = params.get("dump");
    boolean dump = dumpS != null && dumpS.equals("true");

    int start = params.getInt("start", 0); // Note start ignored if rows not specified
    int rows = params.getInt("rows", -1);

    String filterType = params.get("filterType");
    if (filterType != null) {
      filterType = filterType.trim().toLowerCase(Locale.ROOT);
      if (filterType.length() == 0) filterType = null;
    }
    FilterType type = (filterType != null) ? FilterType.valueOf(filterType) : FilterType.none;

    String filter = (type != FilterType.none) ? params.get("filter") : null;
    if (filter != null) {
      filter = filter.trim();
      if (filter.length() == 0) filter = null;
    }

    ZKPrinter printer = new ZKPrinter(coreContainer.getZkController());
    printer.detail = detail;
    printer.dump = dump;
    boolean isGraphView = "graph".equals(params.get("view"));
    // There is no znode /clusterstate.json (removed in Solr 9), but we do as if there's one and
    // return collection listing. Need to change services.js if cleaning up here, collection list is
    // used from Admin UI Cloud - Graph
    boolean paginateCollections = (isGraphView && "/clusterstate.json".equals(path));
    printer.page = paginateCollections ? new PageOfCollections(start, rows, type, filter) : null;
    printer.pagingSupport = pagingSupport;

    try {
      if (paginateCollections) {
        // List collections and allow pagination, but no specific znode info like when looking at a
        // normal ZK path
        response.zookeeperFile = printer.printPaginatedCollections();
      } else {
        response.zookeeperFile = printer.print(path);
      }
    } finally {
      printer.close();
    }

    return response;
  }

  @GET
  @Path("/status")
  @Produces({"application/json", "application/xml", BINARY_CONTENT_TYPE_V2})
  @Operation(
      summary = "Show Zookeeper status.",
      tags = {"zookeeperStatus"})
  @PermissionName(ZK_READ_PERM)
  public ZookeeperStatus getStatus() throws Exception {
    final ZookeeperStatus response = instantiateJerseyResponse(ZookeeperStatus.class);
    if (coreContainer.isZooKeeperAware()) {
      String zkHost = coreContainer.getZkController().getZkServerAddress();
      ZkDynamicConfig dynConfig = null;
      try {
        SolrZkClient zkClient = coreContainer.getZkController().getZkClient();
        dynConfig = ZkDynamicConfig.parseLines(zkClient.getConfig());
      } catch (SolrException e) {
        if (!(e.getCause() instanceof KeeperException)) {
          throw e;
        }
        if (log.isWarnEnabled()) {
          log.warn("{} - Continuing with static connection string", e.toString());
        }
      }
      response.zookeeperStatus.add(
          new ZookeeperStatusHandler(coreContainer).getZkStatus(zkHost, dynConfig));
    } else {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "The Zookeeper status API is only available in Cloud mode");
    }
    return response;
  }

  public static class ZookeeperFilesResponse extends SolrJerseyResponse {

    @JsonProperty("zookeeperFiles")
    public String zookeeperFiles;
  }

  public static class ZookeeperFileResponse extends SolrJerseyResponse {

    @JsonProperty("zookeeperFile")
    public String zookeeperFile;
  }

  public static class ZookeeperStatus extends SolrJerseyResponse {

    @JsonProperty("zookeeperStatus")
    public List<Map<String, Object>> zookeeperStatus = new ArrayList<>();
  }
}
