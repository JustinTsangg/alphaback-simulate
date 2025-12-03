package com.ttsudio.alphaback.simulate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentMatcher;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class SimulateApplicationUnitTest {

    LambdaClient mockLambda;

    @BeforeEach
    public void setup() throws Exception {
        mockLambda = org.mockito.Mockito.mock(LambdaClient.class);

        // load example responses from resources
        String getModelJson = readResource("/getModelResponse.json");
        String gatherJson = readResource("/gatherDataResponse.json");

        // create a minimal getModel response with an empty downloadUrl to avoid real network download
        String innerBody = "{\"classPath\": \"com/ttsudio/alphaback/ExampleModel\", \"downloadUrl\": \"\"}";
        String getModelPayload = "{\"statusCode\":200,\"body\":\"" + innerBody.replace("\"", "\\\"") + "\"}";
        InvokeResponse getModelResp = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(getModelPayload)).build();
        InvokeResponse gatherResp = InvokeResponse.builder()
                .payload(SdkBytes.fromUtf8String(gatherJson)).build();
        // mock serviceConsumer response to point to the gatherData function (ARN may include :function:prefix)
        String serviceJson = readResource("/serviceConsumerResponse.json");
        InvokeResponse serviceResp = InvokeResponse.builder().payload(SdkBytes.fromUtf8String(serviceJson)).build();

        // configure mock for function name
        org.mockito.Mockito.when(mockLambda.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.GET_MODEL_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(getModelResp);

        org.mockito.Mockito.when(mockLambda.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                // allow either the plain function name or the ARN returned by serviceConsumer
                if (SimulateApplication.GATHER_DATA_FUNCTION_NAME.equals(req.functionName())) return true;
                // also accept the ARN's trailing function name (serviceConsumer returns full arn)
                if (req.functionName() != null && req.functionName().endsWith("") && req.functionName().contains("gatherData")) return true;
                return false;
            }
        }))).thenReturn(gatherResp);

        org.mockito.Mockito.when(mockLambda.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.SERVICE_CONSUMER_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(serviceResp);

        // copy ExampleModel.class from classpath into models folder so the loader can find it
        String resourcePath = "/com/ttsudio/alphaback/ExampleModel.class";
        try (InputStream in = this.getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                // resource not present on test classpath; skip copying and assume models folder already contains the class
                System.out.println("ExampleModel.class not found on test classpath; skipping copy: " + resourcePath);
            } else {
                File target = new File("models/com/ttsudio/alphaback");
                target.mkdirs();
                java.io.File dest = new File(target, "ExampleModel.class");
                if (!dest.exists()) {
                    Files.copy(in, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    System.out.println("Target ExampleModel.class already exists; skipping copy: " + dest.getAbsolutePath());
                }
            }
        }
    }

    @Test
    public void simulateRunsAndReturnsResponse() throws Exception {
        SimulateApplication app = new SimulateApplication();

        // inject mock lambda client
        java.lang.reflect.Field f = SimulateApplication.class.getDeclaredField("lambdaClient");
        f.setAccessible(true);
        f.set(app, mockLambda);

        List<String> stocks = Arrays.asList("AAPL", "GOOGL");
        SimulationResponse resp = app.simulate(stocks, "726034f9-44c7-49df-9fac-1241da8ef221", "TIME_SERIES_DAILY");

        assertNotNull(resp);
        assertEquals("OK", resp.getStatus());
        // At least some decisions should be recorded
        assertNotNull(resp.getDecisions());
    }

    @Test
    public void simulateThrowsExceptionWhenClassNotFound() throws Exception {
        LambdaClient mockForMissingClass = org.mockito.Mockito.mock(LambdaClient.class);

        // Return a valid model response but with a class path that doesn't exist
        String innerBody = "{\"classPath\": \"com/ttsudio/alphaback/NonExistentModel\", \"downloadUrl\": \"\"}";
        String getModelPayload = "{\"statusCode\":200,\"body\":\"" + innerBody.replace("\"", "\\\"") + "\"}";
        InvokeResponse getModelResp = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(getModelPayload)).build();

        org.mockito.Mockito.when(mockForMissingClass.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.GET_MODEL_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(getModelResp);

        SimulateApplication app = new SimulateApplication();
        java.lang.reflect.Field f = SimulateApplication.class.getDeclaredField("lambdaClient");
        f.setAccessible(true);
        f.set(app, mockForMissingClass);

        List<String> stocks = Arrays.asList("AAPL");
        
        // expect RuntimeException when attempting to load non-existent class
        try {
            app.simulate(stocks, "test-id", "TIME_SERIES_DAILY");
            throw new AssertionError("Expected RuntimeException for missing class");
        } catch (RuntimeException e) {
            // expected: class not found during loading
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void simulateHandlesServiceConsumerFailureAndFallsBack() throws Exception {
        // Create a new mock that fails for serviceConsumer but succeeds for model and gather calls
        LambdaClient failingServiceConsumer = org.mockito.Mockito.mock(LambdaClient.class);

        String getModelJson = readResource("/getModelResponse.json");
        String gatherJson = readResource("/gatherDataResponse.json");

        String innerBody = "{\"classPath\": \"com/ttsudio/alphaback/ExampleModel\", \"downloadUrl\": \"\"}";
        String getModelPayload = "{\"statusCode\":200,\"body\":\"" + innerBody.replace("\"", "\\\"") + "\"}";
        InvokeResponse getModelResp = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(getModelPayload)).build();
        InvokeResponse gatherResp = InvokeResponse.builder()
                .payload(SdkBytes.fromUtf8String(gatherJson)).build();

        // Setup mocks: serviceConsumer throws, others succeed
        org.mockito.Mockito.when(failingServiceConsumer.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.SERVICE_CONSUMER_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenThrow(new RuntimeException("Service consumer unavailable"));

        org.mockito.Mockito.when(failingServiceConsumer.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.GET_MODEL_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(getModelResp);

        org.mockito.Mockito.when(failingServiceConsumer.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                if (SimulateApplication.GATHER_DATA_FUNCTION_NAME.equals(req.functionName())) return true;
                if (req.functionName() != null && req.functionName().contains("gatherData")) return true;
                return false;
            }
        }))).thenReturn(gatherResp);

        SimulateApplication app = new SimulateApplication();
        java.lang.reflect.Field f = SimulateApplication.class.getDeclaredField("lambdaClient");
        f.setAccessible(true);
        f.set(app, failingServiceConsumer);

        List<String> stocks = Arrays.asList("AAPL", "GOOGL");
        // Should succeed despite serviceConsumer failure; falls back to GATHER_DATA_FUNCTION_NAME
        SimulationResponse resp = app.simulate(stocks, "726034f9-44c7-49df-9fac-1241da8ef221", "TIME_SERIES_DAILY");

        assertNotNull(resp);
        assertEquals("OK", resp.getStatus());
    }

    @Test
    public void simulateHandlesInvalidModelClass() throws Exception {
        // Create a response pointing to a non-existent class that doesn't implement Model
        LambdaClient mockForInvalidClass = org.mockito.Mockito.mock(LambdaClient.class);

        String getModelJson = readResource("/getModelResponse.json");
        String innerBody = "{\"classPath\": \"com/ttsudio/alphaback/InvalidClass\", \"downloadUrl\": \"\"}";
        String getModelPayload = "{\"statusCode\":200,\"body\":\"" + innerBody.replace("\"", "\\\"") + "\"}";
        InvokeResponse getModelResp = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(getModelPayload)).build();

        org.mockito.Mockito.when(mockForInvalidClass.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.GET_MODEL_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(getModelResp);

        SimulateApplication app = new SimulateApplication();
        java.lang.reflect.Field f = SimulateApplication.class.getDeclaredField("lambdaClient");
        f.setAccessible(true);
        f.set(app, mockForInvalidClass);

        List<String> stocks = Arrays.asList("AAPL");
        
        try {
            app.simulate(stocks, "invalid-id", "TIME_SERIES_DAILY");
            throw new AssertionError("Expected RuntimeException for invalid Model class");
        } catch (RuntimeException e) {
            // expected: class not found or doesn't implement Model
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void fetchModelBodyHandlesJsonParseError() throws Exception {
        LambdaClient mockForBadJson = org.mockito.Mockito.mock(LambdaClient.class);

        // Mock response with invalid JSON in body
        String invalidPayload = "{\"statusCode\":200,\"body\":\"not-valid-json\"}";
        InvokeResponse badResp = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(invalidPayload)).build();

        org.mockito.Mockito.when(mockForBadJson.invoke(org.mockito.ArgumentMatchers.any(InvokeRequest.class)))
            .thenReturn(badResp);

        SimulateApplication app = new SimulateApplication();
        java.lang.reflect.Field f = SimulateApplication.class.getDeclaredField("lambdaClient");
        f.setAccessible(true);
        f.set(app, mockForBadJson);

        // Expected: RuntimeException wrapping JsonMappingException
        try {
            app.simulate(Arrays.asList("AAPL"), "test-id", "TIME_SERIES_DAILY");
            throw new AssertionError("Expected RuntimeException for JSON parse error");
        } catch (RuntimeException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void fetchTimeSeriesHandlesLambdaInvocationFailure() throws Exception {
        LambdaClient mockForLambdaFailure = org.mockito.Mockito.mock(LambdaClient.class);

        String innerBody = "{\"classPath\": \"com/ttsudio/alphaback/ExampleModel\", \"downloadUrl\": \"\"}";
        String getModelPayload = "{\"statusCode\":200,\"body\":\"" + innerBody.replace("\"", "\\\"") + "\"}";
        InvokeResponse getModelResp = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(getModelPayload)).build();

        // getModel succeeds, but gatherData lambda throws
        org.mockito.Mockito.when(mockForLambdaFailure.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.GET_MODEL_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(getModelResp);

        org.mockito.Mockito.when(mockForLambdaFailure.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                // Match serviceConsumer calls and return mock response
                if (SimulateApplication.SERVICE_CONSUMER_FUNCTION_NAME.equals(req.functionName())) {
                    return true;
                }
                // Gather data calls will fail
                if (req.functionName() != null && req.functionName().contains("gatherData")) {
                    return true;
                }
                return false;
            }
        }))).thenThrow(new RuntimeException("Gather data lambda unavailable"));

        SimulateApplication app = new SimulateApplication();
        java.lang.reflect.Field f = SimulateApplication.class.getDeclaredField("lambdaClient");
        f.setAccessible(true);
        f.set(app, mockForLambdaFailure);

        // Expected: RuntimeException from lambda invocation
        try {
            app.simulate(Arrays.asList("AAPL"), "test-id", "TIME_SERIES_DAILY");
            throw new AssertionError("Expected RuntimeException for lambda invocation failure");
        } catch (RuntimeException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void simulateHandlesClassPathNormalization() throws Exception {
        // Test that the classpath normalization logic handles dot notation (lines 199-212)
        // The app normalizes classPathRaw by converting dots/backslashes to slashes and appending .class
        
        // Test with dot notation: "com.ttsudio.alphaback.ExampleModel" should become "com/ttsudio/alphaback/ExampleModel.class"
        LambdaClient mockDotNotation = org.mockito.Mockito.mock(LambdaClient.class);

        String innerBodyDot = "{\"classPath\": \"com.ttsudio.alphaback.ExampleModel\", \"downloadUrl\": \"\"}";
        String getModelPayloadDot = "{\"statusCode\":200,\"body\":\"" + innerBodyDot.replace("\"", "\\\"") + "\"}";
        InvokeResponse getModelRespDot = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(getModelPayloadDot)).build();
        
        String gatherJson = readResource("/gatherDataResponse.json");
        InvokeResponse gatherResp = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(gatherJson)).build();

        org.mockito.Mockito.when(mockDotNotation.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.GET_MODEL_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(getModelRespDot);

        org.mockito.Mockito.when(mockDotNotation.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                if (SimulateApplication.GATHER_DATA_FUNCTION_NAME.equals(req.functionName())) return true;
                if (req.functionName() != null && req.functionName().contains("gatherData")) return true;
                return false;
            }
        }))).thenReturn(gatherResp);

        org.mockito.Mockito.when(mockDotNotation.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.SERVICE_CONSUMER_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(InvokeResponse.builder().payload(SdkBytes.fromUtf8String(readResource("/serviceConsumerResponse.json"))).build());

        SimulateApplication app1 = new SimulateApplication();
        java.lang.reflect.Field f1 = SimulateApplication.class.getDeclaredField("lambdaClient");
        f1.setAccessible(true);
        f1.set(app1, mockDotNotation);

        // Test with dot notation: should normalize internally and find ExampleModel
        SimulationResponse resp1 = app1.simulate(Arrays.asList("AAPL"), "test-id", "TIME_SERIES_DAILY");
        assertNotNull(resp1);
        assertEquals("OK", resp1.getStatus());

        // Test with slash notation already in classpath (should also work)
        LambdaClient mockSlashNotation = org.mockito.Mockito.mock(LambdaClient.class);

        String innerBodySlash = "{\"classPath\": \"com/ttsudio/alphaback/ExampleModel\", \"downloadUrl\": \"\"}";
        String getModelPayloadSlash = "{\"statusCode\":200,\"body\":\"" + innerBodySlash.replace("\"", "\\\"") + "\"}";
        InvokeResponse getModelRespSlash = InvokeResponse.builder()
            .payload(SdkBytes.fromUtf8String(getModelPayloadSlash)).build();

        org.mockito.Mockito.when(mockSlashNotation.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.GET_MODEL_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(getModelRespSlash);

        org.mockito.Mockito.when(mockSlashNotation.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                if (SimulateApplication.GATHER_DATA_FUNCTION_NAME.equals(req.functionName())) return true;
                if (req.functionName() != null && req.functionName().contains("gatherData")) return true;
                return false;
            }
        }))).thenReturn(gatherResp);

        org.mockito.Mockito.when(mockSlashNotation.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.SERVICE_CONSUMER_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(InvokeResponse.builder().payload(SdkBytes.fromUtf8String(readResource("/serviceConsumerResponse.json"))).build());

        SimulateApplication app2 = new SimulateApplication();
        java.lang.reflect.Field f2 = SimulateApplication.class.getDeclaredField("lambdaClient");
        f2.setAccessible(true);
        f2.set(app2, mockSlashNotation);

        // Test with slash notation: should also work
        SimulationResponse resp2 = app2.simulate(Arrays.asList("AAPL"), "test-id", "TIME_SERIES_DAILY");
        assertNotNull(resp2);
        assertEquals("OK", resp2.getStatus());
    }

    @Test
    public void simulateDownloadsToNormalizedPath_usingFileUrl() throws Exception {
        // This test forces the download branch to run by providing a file:// URL and
        // a classPath that normalizes to a different target path. We then assert
        // that the file was copied to the normalized target location.

        // locate existing ExampleModel.class which is present in models/... from previous setup
        java.io.File existing = new java.io.File("models/com/ttsudio/alphaback/ExampleModel.class");
        if (!existing.exists()) {
            throw new RuntimeException("Existing ExampleModel.class not found at " + existing.getAbsolutePath());
        }

        // choose a different classPath so the normalized target is models/downloaded/ExampleModel.class
        String classPathRaw = "downloaded.ExampleModel";
        String downloadUrl = new java.io.File(existing.getAbsolutePath()).toURI().toString(); // file:// URL

        // build getModel response with a real file:// downloadUrl
        String innerBody = "{\"classPath\": \"" + classPathRaw + "\", \"downloadUrl\": \"" + downloadUrl + "\"}";
        String getModelPayload = "{\"statusCode\":200,\"body\":\"" + innerBody.replace("\"", "\\\"") + "\"}";
        InvokeResponse getModelResp = InvokeResponse.builder().payload(SdkBytes.fromUtf8String(getModelPayload)).build();

        String gatherJson = readResource("/gatherDataResponse.json");
        InvokeResponse gatherResp = InvokeResponse.builder().payload(SdkBytes.fromUtf8String(gatherJson)).build();

        LambdaClient mockClient = org.mockito.Mockito.mock(LambdaClient.class);
        org.mockito.Mockito.when(mockClient.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.GET_MODEL_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(getModelResp);

        org.mockito.Mockito.when(mockClient.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                if (SimulateApplication.GATHER_DATA_FUNCTION_NAME.equals(req.functionName())) return true;
                if (req.functionName() != null && req.functionName().contains("gatherData")) return true;
                return false;
            }
        }))).thenReturn(gatherResp);

        org.mockito.Mockito.when(mockClient.invoke(org.mockito.ArgumentMatchers.argThat(new ArgumentMatcher<InvokeRequest>() {
            @Override
            public boolean matches(InvokeRequest req) {
                if (req == null) return false;
                return SimulateApplication.SERVICE_CONSUMER_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(InvokeResponse.builder().payload(SdkBytes.fromUtf8String(readResource("/serviceConsumerResponse.json"))).build());

        // ensure target doesn't exist
        java.io.File target = new java.io.File("models/downloaded/ExampleModel.class");
        if (target.exists()) target.delete();

        SimulateApplication app = new SimulateApplication();
        java.lang.reflect.Field f = SimulateApplication.class.getDeclaredField("lambdaClient");
        f.setAccessible(true);
        f.set(app, mockClient);

        try {
            app.simulate(Arrays.asList("AAPL"), "test-id", "TIME_SERIES_DAILY");
        } catch (Throwable ignore) {
            // loading will likely fail because the copied class has a different package/name,
            // but the download and normalization should still have occurred.
        }

        // assert the normalized target file was created by the download step
        org.junit.jupiter.api.Assertions.assertTrue(target.exists(), "Expected downloaded class at " + target.getAbsolutePath());

        // cleanup
        if (target.exists()) target.delete();
    }

    private String readResource(String path) throws Exception {
        try (InputStream in = this.getClass().getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("Resource not found: " + path);
            return new String(in.readAllBytes());
        }
    }
}
