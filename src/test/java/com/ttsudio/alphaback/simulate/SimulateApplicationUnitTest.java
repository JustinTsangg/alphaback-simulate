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
                return SimulateApplication.GATHER_DATA_FUNCTION_NAME.equals(req.functionName());
            }
        }))).thenReturn(gatherResp);

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

    private String readResource(String path) throws Exception {
        try (InputStream in = this.getClass().getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("Resource not found: " + path);
            return new String(in.readAllBytes());
        }
    }
}
