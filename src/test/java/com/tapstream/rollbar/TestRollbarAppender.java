package com.tapstream.rollbar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestRollbarAppender {
    
    String apiKey = "api key";
    String endpoint = "http://rollbar.endpoint/";
    String env = "test";

    LoggerContext loggerContext;
    RollbarAppender appender;
    MockHttpRequester httpRequester;
    Logger rootLogger;

    @Before
    public void setup() {
        System.setProperty("log4j.configurationFile", "log4j.test.xml");
        httpRequester = new MockHttpRequester();
        rootLogger = LogManager.getLogger();
        Map<String, Appender> appenderMap = ((org.apache.logging.log4j.core.Logger) rootLogger).getAppenders();

        appender = (RollbarAppender) appenderMap.get("rollbar");
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

        JSONObject root = new JSONObject(new String(request.getBody()));
        assertEquals(apiKey, root.get("access_token"));
        
        JSONObject data = root.getJSONObject("data");
        assertEquals(env, data.get("environment"));
        assertEquals("info", data.get("level"));
        assertEquals("java", data.get("platform"));
        assertEquals("java", data.get("language"));
        assertEquals("java", data.get("framework"));
        assertEquals("098f6bcd4621d373cade4e832627b4f6", data.get("fingerprint"));
        
        JSONObject body = data.getJSONObject("body");
        assertEquals(testMsg, body.getJSONObject("message").get("body"));
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
        
        JSONObject root = new JSONObject(new String(request.getBody()));
        assertEquals(apiKey, root.get("access_token"));
        
        JSONObject data = root.getJSONObject("data");
        assertEquals(env, data.get("environment"));
        assertEquals("error", data.get("level"));
        assertEquals("java", data.get("platform"));
        assertEquals("java", data.get("language"));
        assertEquals("java", data.get("framework"));
    
        JSONObject body = data.getJSONObject("body");
        JSONArray traceChain = body.getJSONArray("trace_chain");
        JSONObject firstTrace = traceChain.getJSONObject(0);
        JSONArray frames = firstTrace.getJSONArray("frames");
        JSONObject lastFrame = frames.getJSONObject(frames.length() - 1);
        assertEquals("TestRollbarAppender.java", lastFrame.get("filename"));
        assertEquals("testThrowable", lastFrame.get("method"));
        assertEquals("com.tapstream.rollbar.TestRollbarAppender", lastFrame.get("class_name"));
        JSONObject firstException = firstTrace.getJSONObject("exception");
        assertEquals(testThrowableMsg, firstException.get("message"));
        assertEquals("java.lang.Exception", firstException.get("class"));
        
        JSONObject custom = data.getJSONObject("custom");
        assertEquals(testMsg, custom.get("log"));
    }
    
    
    

}
