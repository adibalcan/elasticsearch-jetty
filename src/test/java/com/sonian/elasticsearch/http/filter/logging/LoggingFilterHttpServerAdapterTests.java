package com.sonian.elasticsearch.http.filter.logging;

import com.sonian.elasticsearch.http.filter.FilterHttpServerTransport;
import com.sonian.elasticsearch.http.filter.FilterHttpServerTransportModule;
import com.sonian.elasticsearch.http.jetty.AbstractJettyHttpServerTests;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.logging.log4j.Log4jESLoggerFactory;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.node.internal.InternalNode;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.sonian.elasticsearch.http.filter.logging.RequestLoggingLevel.Level.*;
import static org.elasticsearch.rest.RestRequest.Method.GET;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * @author imotov
 */
public class LoggingFilterHttpServerAdapterTests extends AbstractJettyHttpServerTests {

    private MockESLoggerFactory mockESLoggerFactory;

    @BeforeMethod
    public void setup() {
        mockESLoggerFactory = new MockESLoggerFactory("INFO", "com.sonian.elasticsearch.http.filter.logging");
        ESLoggerFactory.setDefaultFactory(mockESLoggerFactory);
        putDefaultSettings(ImmutableSettings.settingsBuilder()
                .put("http.type", FilterHttpServerTransportModule.class.getName())
        );
    }

    @AfterMethod
    public void closeNodes() {
        closeAllNodes();
        ESLoggerFactory.setDefaultFactory(mockESLoggerFactory.realFactory);
    }

    @Test
    public void testClusterHealth() throws Exception {
        startNode("server1");
        createTestIndex();
        // Shouldn't log cluster health call
        Map<String, Object> response = httpClient("server1").request("_cluster/health");
        assertThat((String) response.get("status"), equalTo("green"));

        Map<String, Object> data = createSearchQuery("user:kimchy");
        httpClient("server1").request("POST", "_search", data);
        // Should start with logging for the POST /_search request
        String logMessage = mockESLoggerFactory.getMessage();
        assertThat(logMessage, startsWith("INFO:[server1] POST /_search - 200 OK"));
        // should contain request body
        assertThat(logMessage, containsString("user:kimchy"));
    }

    @Test
    public void testLoggingSettings() throws Exception {
        startNode("server1");
        FilterHttpServerTransport filterHttpServerTransport = (FilterHttpServerTransport)
                ((InternalNode) node("server1")).injector().getInstance(HttpServerTransport.class);
        LoggingFilterHttpServerAdapter logging = (LoggingFilterHttpServerAdapter) filterHttpServerTransport.filter("logging");
        assertThat(logging.requestLoggingLevelSettings().getLoggingLevel(GET, "/_bulk").logBody(), equalTo(false));
        assertThat(logging.requestLoggingLevelSettings().getLoggingLevel(GET, "/idx/_bulk").logBody(), equalTo(false));
        assertThat(logging.requestLoggingLevelSettings().getLoggingLevel(GET, "/_cluster/health").logLevel(), equalTo(TRACE));
    }

    @Test
    public void testEmptyLoggingSettings() throws Exception {
        // Replace logger with custom settings with logger2 with default settings
        putDefaultSettings(ImmutableSettings.settingsBuilder()
                .putArray("sonian.elasticsearch.http.filter.http_filter_chain", "timeout", "logging2")
                .put("sonian.elasticsearch.http.filter.http_filter.logging2.type",
                        "com.sonian.elasticsearch.http.filter.logging.LoggingFilterHttpServerAdapter")
        );
        startNode("server1");
        createTestIndex();

        // Should log cluster health call
        Map<String, Object> response = httpClient("server1").request("_cluster/health");
        assertThat((String) response.get("status"), equalTo("green"));
        assertThat(mockESLoggerFactory.getMessage(), startsWith("INFO:[server1] GET /_cluster/health - 200 OK"));

        Map<String, Object> data = createSearchQuery("user:kimchy");
        httpClient("server1").request("POST", "_search", data);
        String logMessage = mockESLoggerFactory.getMessage();
        // Should start with logging for the POST /_search request
        assertThat(logMessage, startsWith("INFO:[server1] POST /_search - 200 OK"));
        // shouldn't contain request body
        assertThat(logMessage, not(containsString("user:kimchy")));
    }

    private Map<String, Object> createSearchQuery(String queryString) {
        return MapBuilder.<String, Object>newMapBuilder()
                .put("query", MapBuilder.newMapBuilder()
                        .put("query_string", MapBuilder.newMapBuilder()
                                .put("query", queryString)
                                .immutableMap()
                        ).immutableMap()
                ).immutableMap();
    }

    private class QueueMessageCollector implements CollectingESLogger.LogMessageCollector {
        private LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<String>();

        public void log(String message) {
            try {
                messages.put(message);
            } catch (InterruptedException ex) {
                // Ignore
            }
        }

        public String getMessage(int timeout, TimeUnit unit) throws InterruptedException{
            return messages.poll(timeout, unit);
        }

    }

    private class MockESLoggerFactory extends ESLoggerFactory {
        private final ESLoggerFactory realFactory = new Log4jESLoggerFactory();

        private final QueueMessageCollector messageCollector = new QueueMessageCollector();

        private final String level;

        private final String suffix;

        public MockESLoggerFactory(String level, String suffix) {
            this.level = level;
            this.suffix = suffix;
        }

        @Override
        protected ESLogger newInstance(String prefix, String name) {
            if (name.endsWith(suffix)) {
                logger.info("Asked for Logger " + prefix + ":" + name);
                return new CollectingESLogger(level, messageCollector, prefix, name);
            }
            return realFactory.newInstance(name);
        }

        public String getMessage(int timeout, TimeUnit unit) {
            try {
                return messageCollector.getMessage(timeout, unit);
            } catch (InterruptedException ex) {
                return null;
            }
        }

        public String getMessage() {
            return getMessage(1, TimeUnit.SECONDS);
        }
    }

    private void createTestIndex() {
        try {
            client("server1").admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client("server1").admin().indices().prepareCreate("test")
                .setSettings(
                        ImmutableSettings.settingsBuilder()
                                .put("number_of_shards", 1)
                                .put("number_of_replicas", 0))
                .execute().actionGet();
        client("server1").admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
    }
}