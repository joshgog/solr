/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.client.solrj.embedded;

import static org.apache.solr.SolrTestCaseJ4.DEFAULT_TEST_COLLECTION_NAME;

import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.solr.client.solrj.LargeVolumeTestBase;
import org.apache.solr.util.ExternalPaths;
import org.junit.BeforeClass;

public class LargeVolumeJettyTest extends LargeVolumeTestBase {
  @BeforeClass
  public static void beforeTest() throws Exception {
    // TODO
    solrClientTestRule.startSolr(LuceneTestCase.createTempDir("solrhome"));

    solrClientTestRule
        .newCollection(DEFAULT_TEST_COLLECTION_NAME)
        .withConfigSet(ExternalPaths.TECHPRODUCTS_CONFIGSET)
        .create();
  }
}
