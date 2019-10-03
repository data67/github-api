package org.kohsuke.github.junit;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.status;

/**
 * @author Liam Newman
 */
public class GitHubApiWireMockRule extends WireMockRule {

    // By default the wiremock tests will run without proxy or taking a snapshot.
    // The tests will use only the stubbed data and will fail if requests are made for missing data.
    // You can use the proxy without taking a snapshot while writing and debugging tests.
    // You cannot take a snapshot without proxying.
    private final static boolean takeSnapshot = System.getProperty("test.github.takeSnapshot", "false") != "false";
    private final static boolean useProxy = takeSnapshot || System.getProperty("test.github.useProxy", "false") != "false";

    public GitHubApiWireMockRule(WireMockConfiguration options) {
        this(options, true);
    }

    public GitHubApiWireMockRule(WireMockConfiguration options, boolean failOnUnmatchedRequests) {
        super(options
                .extensions(
                    new ResponseTransformer() {
                        @Override
                        public Response transform(Request request, Response response, FileSource files,
                                                  Parameters parameters) {
                            if ("application/json"
                                .equals(response.getHeaders().getContentTypeHeader().mimeTypePart())
                                && !response.getHeaders().getHeader("Content-Encoding").containsValue("gzip")) {
                                return Response.Builder.like(response)
                                    .but()
                                    .body(response.getBodyAsString()
                                        .replace("https://api.github.com/",
                                            "http://localhost:" + request.getPort() + "/")
                                    )
                                    .build();
                            }
                            return response;
                        }

                        @Override
                        public String getName() {
                            return "github-api-url-rewrite";
                        }
                    }),
            failOnUnmatchedRequests);
    }

    public boolean isUseProxy() {
        return GitHubApiWireMockRule.useProxy;
    }

    public boolean isTakeSnapshot() {
        return GitHubApiWireMockRule.takeSnapshot;
    }

    @Override
    protected void before() {
        super.before();
        if (isUseProxy()) {
            this.stubFor(
                proxyAllTo("https://api.github.com/")
                    .atPriority(100)
            );
        } else {
            // Just to be super clear
            this.stubFor(
                any(urlPathMatching(".*"))
                    .willReturn(status(500).withBody("Stubbed data not found. Set test.github.use-proxy to have WireMock proxy to github"))
                    .atPriority(100));
        }
    }

    @Override
    protected void after() {
        super.after();
        // To reformat everything
        //formatJsonFiles(new File("src/test/resources").toPath());

        if (isTakeSnapshot()) {
            this.snapshotRecord(recordSpec()
                .forTarget("https://api.github.com")
                .captureHeader("If-None-Match")
                .extractTextBodiesOver(255));

            // After taking the snapshot, format the output
            // Disabled for now as the output is more confusing than helpful
            // formatJsonFiles(new File(this.getOptions().filesRoot().getPath()).toPath());
        }
    }

    private void formatJsonFiles(Path path) {
        try {
            Files.walk(path)
                .forEach(filePath -> {
                    try {
                        if (filePath.toString().endsWith(".json")) {
                            String fileText = new String(Files.readAllBytes(filePath));
                            if (fileText.startsWith("{")) {
                                fileText = new JSONObject(new String(Files.readAllBytes(filePath))).toString(2);
                            } else {
                                fileText = new JSONArray(new String(Files.readAllBytes(filePath))).toString(2);
                            }

                            Files.write(filePath, fileText.getBytes());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Files could not be written", e);
                    }
                });
        } catch (IOException e) {
            throw new RuntimeException("Files could not be written");
        }
    }
}