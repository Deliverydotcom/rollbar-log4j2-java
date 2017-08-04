package com.tapstream.rollbar;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestRollbarAppender {
    
    private String apiKey = "api key";
    private String endpoint = "http://rollbar.endpoint/";
    private String env = "test";

    private MockHttpRequester httpRequester;
    private Logger rootLogger;

    @Before
    public void setup() {
        System.setProperty("log4j.configurationFile", "log4j.test.xml");
        httpRequester = new MockHttpRequester();
        rootLogger = LogManager.getLogger();
        Map<String, Appender> appenderMap = ((org.apache.logging.log4j.core.Logger) rootLogger).getAppenders();

        RollbarAppender appender = (RollbarAppender) appenderMap.get("rollbar");
        appender.setUrl(endpoint);
        appender.setEnvironment(env);
        appender.setApiKey(apiKey);
        appender.setHttpRequester(httpRequester);
        ((org.apache.logging.log4j.core.Logger) rootLogger).addAppender(appender);

        LoggerContext context = (LoggerContext) LogManager.getContext();
        Configuration config = context.getConfiguration();
        config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME).addAppender(appender, null, null);

        context.updateLoggers(config);

    }
    
    @After
    public void teardown(){
        
    }
    
    private void checkCommonRequestFields(HttpRequest request) {
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals(endpoint, request.getUrl().toString());
    }
    
    @Test
    public void testMessage() throws Exception {
        String testMsg = "test";
        rootLogger.info(testMsg);
        HttpRequest request  = httpRequester.getRequest();
        checkCommonRequestFields(request);

        JsonObject root = new JsonParser().parse(new String(request.getBody())).getAsJsonObject();
        assertEquals(apiKey, root.get("access_token").getAsString());
        
        JsonObject data = root.getAsJsonObject("data");
        assertEquals(env, data.get("environment").getAsString());
        assertEquals("info", data.get("level").getAsString());
        assertEquals("java", data.get("platform").getAsString());
        assertEquals("java", data.get("language").getAsString());
        assertEquals("java", data.get("framework").getAsString());
        assertEquals("098f6bcd4621d373cade4e832627b4f6", data.get("fingerprint").getAsString());
        
        JsonObject body = data.getAsJsonObject("body");
        assertEquals(testMsg, body.getAsJsonObject("message").get("body").getAsString());
    }
    
    @Test
    public void testMessageSendError() throws Exception {
        String testMsg = "test";
        httpRequester.setResponseCode(500);
        rootLogger.info(testMsg);
    }
    
    @Test
    public void testThrowable() throws Exception {
        String testMsg = "test error";
        String testThrowableMsg = "test throwable";
        Throwable throwable = new Exception(testThrowableMsg);
        rootLogger.error(testMsg, throwable);
        HttpRequest request  = httpRequester.getRequest();
        checkCommonRequestFields(request);
        
        JsonObject root = new JsonParser().parse(new String(request.getBody())).getAsJsonObject();
        assertEquals(apiKey, root.get("access_token").getAsString());
        
        JsonObject data = root.getAsJsonObject("data");
        assertEquals(env, data.get("environment").getAsString());
        assertEquals("error", data.get("level").getAsString());
        assertEquals("java", data.get("platform").getAsString());
        assertEquals("java", data.get("language").getAsString());
        assertEquals("java", data.get("framework").getAsString());
    
        JsonObject body = data.getAsJsonObject("body");
        JsonArray traceChain = body.getAsJsonArray("trace_chain");
        JsonObject firstTrace = traceChain.get(0).getAsJsonObject();
        JsonArray frames = firstTrace.getAsJsonArray("frames");
        JsonObject lastFrame = frames.get(frames.size() - 1).getAsJsonObject();
        assertEquals("TestRollbarAppender.java", lastFrame.get("filename").getAsString());
        assertEquals("testThrowable", lastFrame.get("method").getAsString());
        assertEquals("com.tapstream.rollbar.TestRollbarAppender", lastFrame.get("class_name").getAsString());
        JsonObject firstException = firstTrace.getAsJsonObject("exception");
        assertEquals(testThrowableMsg, firstException.get("message").getAsString());
        assertEquals("java.lang.Exception", firstException.get("class").getAsString());
        
        JsonObject custom = data.getAsJsonObject("custom");
        assertEquals(testMsg, custom.get("log").getAsString());
    }
    
    
    

}
