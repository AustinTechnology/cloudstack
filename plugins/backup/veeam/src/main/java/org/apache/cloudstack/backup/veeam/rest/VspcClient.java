// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup.veeam.rest;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The little of the Veeam Service Provider Console REST API (v3) the provider needs: which company a Veeam
 * Backup &amp; Replication job is assigned to, and assigning it, so that VSPC bills each instance's own job to its
 * customer. VSPC knows a job by its Veeam job id once it has collected it from the backup server.
 */
public class VspcClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public VspcClient(String url, String apiKey, boolean validateCertificate, int timeoutSeconds) throws NoSuchAlgorithmException, KeyManagementException {
        String base = StringUtils.removeEnd(url.trim(), "/");
        this.baseUrl = base.endsWith("/api/v3") ? base : base + "/api/v3";
        this.apiKey = apiKey;
        RequestConfig config = RequestConfig.custom().setConnectTimeout(timeoutSeconds * 1000)
                .setConnectionRequestTimeout(timeoutSeconds * 1000).setSocketTimeout(timeoutSeconds * 1000).build();
        HttpClientBuilder builder = HttpClientBuilder.create().setDefaultRequestConfig(config);
        if (!validateCertificate) {
            SSLContext context = SSLUtils.getSSLContext();
            context.init(null, new X509TrustManager[]{new TrustAllManager()}, new SecureRandom());
            builder.setSSLSocketFactory(new SSLConnectionSocketFactory(context, NoopHostnameVerifier.INSTANCE));
        }
        this.httpClient = builder.build();
    }

    /** The company (organization) UID the job is assigned to; null when VSPC does not know the job yet. */
    public String jobCompany(String jobId) {
        JsonNode job = send(new HttpGet(baseUrl + "/infrastructure/backupServers/jobs/" + enc(jobId)), true);
        if (job == null) {
            return null;
        }
        JsonNode uid = job.path("data").path("mappedOrganizationUid");
        return uid.isMissingNode() || uid.isNull() ? "" : uid.asText();
    }

    /** Assigns the job to the company (VSPC queues it). */
    public void assignJob(String jobId, String companyUid) {
        send(new HttpPost(baseUrl + "/infrastructure/backupServers/jobs/" + enc(jobId) + "/assign?companyUid=" + enc(companyUid)), false);
    }

    private JsonNode send(HttpRequestBase request, boolean notFoundIsNull) {
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        String what = request.getMethod() + " " + request.getURI().getPath();
        try {
            HttpResponse response = httpClient.execute(request);
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status == 404 && notFoundIsNull) {
                return null;
            }
            if (status < 200 || status >= 300) {
                String message = "";
                try {
                    JsonNode error = MAPPER.readTree(body);
                    message = StringUtils.firstNonBlank(error.path("errors").path(0).path("message").asText(""), error.path("message").asText(""), "");
                } catch (IOException e) {
                    // not JSON
                }
                throw new CloudRuntimeException(String.format("VSPC API %s failed with HTTP %d %s", what, status, message).trim());
            }
            return StringUtils.isBlank(body) ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (IOException e) {
            throw new CloudRuntimeException(String.format("VSPC API %s failed: %s", what, e.getMessage()), e);
        } finally {
            request.releaseConnection();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
