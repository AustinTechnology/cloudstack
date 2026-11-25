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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client for the Veeam Backup &amp; Replication REST API (v12+, tested with 1.3-rev2 / VBR 13.1).
 * Covers the platforms the older Enterprise Manager client cannot: Proxmox VE and Microsoft Hyper-V.
 * Responses are read as JSON trees: only the few fields the provider needs are used.
 */
public class VbrRestClient {
    private static final Logger LOG = LogManager.getLogger(VbrRestClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PAGE = 500;

    private final String baseUrl;
    private final String apiVersion;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private String accessToken;

    public VbrRestClient(String url, String apiVersion, String username, String password, boolean validateCertificate, int timeoutSeconds)
            throws NoSuchAlgorithmException, KeyManagementException {
        this.baseUrl = StringUtils.removeEnd(url.trim(), "/");
        this.apiVersion = apiVersion;
        this.username = username;
        this.password = password;
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

    // ---- model ----

    /** A Veeam job the provider can offer: Proxmox and Hyper-V backup jobs. */
    public static class JobState {
        public String id, name, type, status, lastResult, lastRun, nextRun, sessionId;
    }

    /** One machine in a Veeam backup. objectId is the hypervisor's own VM id (Proxmox SMBIOS UUID, Hyper-V GUID). */
    public static class BackupObject {
        public String id, objectId, name, platformName, backupId;
    }

    public static class RestorePoint {
        public String id, creationTime, type, backupId, backupFileId;
        public List<String> allowedOperations = new ArrayList<>();
    }

    public static class BackupFile {
        public String id;
        public long dataSize, backupSize;
        public List<String> restorePointIds = new ArrayList<>();
    }

    public static class Session {
        public String id, state, result, message;
    }

    // ---- API ----

    public List<JobState> listJobStates() {
        List<JobState> jobs = new ArrayList<>();
        for (JsonNode n : getAll("/api/v1/jobs/states", null)) {
            JobState j = new JobState();
            j.id = text(n, "id"); j.name = text(n, "name"); j.type = text(n, "type"); j.status = text(n, "status");
            j.lastResult = text(n, "lastResult"); j.lastRun = text(n, "lastRun"); j.nextRun = text(n, "nextRun");
            j.sessionId = text(n, "sessionId");
            jobs.add(j);
        }
        return jobs;
    }

    public Map<String, String> listBackupJobIds() {
        Map<String, String> backupToJob = new LinkedHashMap<>();
        for (JsonNode n : getAll("/api/v1/backups", null)) {
            backupToJob.put(text(n, "id"), text(n, "jobId"));
        }
        return backupToJob;
    }

    public List<BackupObject> listBackupObjects() {
        List<BackupObject> objects = new ArrayList<>();
        for (JsonNode n : getAll("/api/v1/backupObjects", null)) {
            BackupObject o = new BackupObject();
            o.id = text(n, "id"); o.objectId = text(n, "objectId"); o.name = text(n, "name");
            o.platformName = text(n, "platformName"); o.backupId = text(n, "backupId");
            objects.add(o);
        }
        return objects;
    }

    public List<RestorePoint> listRestorePoints(String backupObjectId) {
        List<RestorePoint> points = new ArrayList<>();
        for (JsonNode n : getAll("/api/v1/backupObjects/" + enc(backupObjectId) + "/restorePoints", null)) {
            RestorePoint p = new RestorePoint();
            p.id = text(n, "id"); p.creationTime = text(n, "creationTime"); p.type = text(n, "type");
            p.backupId = text(n, "backupId"); p.backupFileId = text(n, "backupFileId");
            n.path("allowedOperations").forEach(op -> p.allowedOperations.add(op.asText()));
            points.add(p);
        }
        return points;
    }

    public List<BackupFile> listBackupFiles(String backupId) {
        List<BackupFile> files = new ArrayList<>();
        for (JsonNode n : getAll("/api/v1/backups/" + enc(backupId) + "/backupFiles", null)) {
            BackupFile f = new BackupFile();
            f.id = text(n, "id"); f.dataSize = n.path("dataSize").asLong(0); f.backupSize = n.path("backupSize").asLong(0);
            n.path("restorePointIds").forEach(id -> f.restorePointIds.add(id.asText()));
            files.add(f);
        }
        return files;
    }

    /** Starts a whole job (incremental unless activeFull). Returns the new session. */
    public Session startJob(String jobId, boolean activeFull) {
        JsonNode n = send(new HttpPost(url("/api/v1/jobs/" + enc(jobId) + "/start", null)),
                "{\"performActiveFull\":" + activeFull + "}");
        return session(n);
    }

    /** The job as Veeam returns it (Hyper-V jobs only; Proxmox jobs are not readable through the REST API). */
    public JsonNode getJob(String jobId) {
        return send(new HttpGet(url("/api/v1/jobs/" + enc(jobId), null)), null);
    }

    /** Replaces the job with the given model (as read with getJob, edited). Needs the Backup Administrator role. */
    public void putJob(String jobId, JsonNode job) {
        HttpPut put = new HttpPut(url("/api/v1/jobs/" + enc(jobId), null));
        try {
            send(put, MAPPER.writeValueAsString(job));
        } catch (IOException e) {
            throw new CloudRuntimeException(e);
        }
    }

    /** The Hyper-V inventory object of the VM with this GUID, searched on every Hyper-V server Veeam manages; null if none. */
    public JsonNode findHyperVVm(String guid) {
        String body = "{\"filter\":{\"type\":\"PredicateExpression\",\"operation\":\"in\",\"property\":\"Type\",\"value\":\"VirtualMachine\"},"
                + "\"pagination\":{\"skip\":0,\"limit\":10000}}";
        for (JsonNode server : send(new HttpPost(url("/api/v1/inventory", null)), "{}").path("data")) {
            if (!"HyperV".equals(text(server, "platform"))) {
                continue;
            }
            for (JsonNode o : send(new HttpPost(url("/api/v1/inventory/" + enc(text(server, "hostName")), null)), body).path("data")) {
                if ("VirtualMachine".equals(text(o, "type")) && guid.equalsIgnoreCase(text(o, "objectId"))) {
                    return o;
                }
            }
        }
        return null;
    }

    /** Ad-hoc incremental of one Hyper-V VM (its inventory object) that already has a full backup. */
    public Session quickBackupHyperV(JsonNode vm) {
        try {
            return session(send(new HttpPost(url("/api/v1/jobs/quickBackup/hyperV", null)), MAPPER.writeValueAsString(vm)));
        } catch (IOException e) {
            throw new CloudRuntimeException(e);
        }
    }

    public Session getSession(String sessionId) {
        return session(send(new HttpGet(url("/api/v1/sessions/" + enc(sessionId), null)), null));
    }

    // ---- transport ----

    private Session session(JsonNode n) {
        Session s = new Session();
        s.id = text(n, "id"); s.state = text(n, "state");
        s.result = n.path("result").path("result").asText(null); s.message = n.path("result").path("message").asText(null);
        return s;
    }

    private List<JsonNode> getAll(String path, Map<String, String> filters) {
        List<JsonNode> items = new ArrayList<>();
        int skip = 0;
        while (true) {
            Map<String, String> query = new LinkedHashMap<>();
            if (filters != null) {
                query.putAll(filters);
            }
            query.put("skip", String.valueOf(skip));
            query.put("limit", String.valueOf(PAGE));
            JsonNode page = send(new HttpGet(url(path, query)), null);
            JsonNode data = page.path("data");
            data.forEach(items::add);
            long total = page.path("pagination").path("total").asLong(items.size());
            if (data.size() == 0 || items.size() >= total) {
                return items;
            }
            skip += data.size();
        }
    }

    private synchronized String token() {
        if (accessToken == null) {
            HttpPost post = new HttpPost(baseUrl + "/api/oauth2/token");
            post.setHeader("x-api-version", apiVersion);
            String form = "grant_type=password&username=" + enc(username) + "&password=" + enc(password);
            post.setEntity(new StringEntity(form, ContentType.APPLICATION_FORM_URLENCODED));
            JsonNode n = execute(post, false);
            accessToken = text(n, "access_token");
            if (StringUtils.isBlank(accessToken)) {
                throw new CloudRuntimeException("Veeam REST API login returned no access token");
            }
        }
        return accessToken;
    }

    private JsonNode send(HttpRequestBase request, String jsonBody) {
        request.setHeader("x-api-version", apiVersion);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        if (request instanceof HttpEntityEnclosingRequestBase) {
            ((HttpEntityEnclosingRequestBase) request).setEntity(new StringEntity(jsonBody == null ? "{}" : jsonBody, ContentType.APPLICATION_JSON));
        }
        try {
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token());
            return execute(request, true);
        } catch (TokenExpired e) {
            synchronized (this) {
                accessToken = null;
            }
            request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token());
            return execute(request, false);
        }
    }

    private static class TokenExpired extends RuntimeException {
    }

    private JsonNode execute(HttpRequestBase request, boolean retryOn401) {
        String what = request.getMethod() + " " + request.getURI().getPath();
        try {
            HttpResponse response = httpClient.execute(request);
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status == 401 && retryOn401) {
                throw new TokenExpired();
            }
            if (status < 200 || status >= 300) {
                // Veeam error bodies carry errorCode/message only, never credentials.
                String message = StringUtils.isBlank(body) ? "" : MAPPER.readTree(body).path("message").asText("");
                throw new CloudRuntimeException(String.format("Veeam REST API %s failed with HTTP %d %s", what, status, message).trim());
            }
            return StringUtils.isBlank(body) ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (IOException e) {
            LOG.debug("Veeam REST API {} failed", what, e);
            throw new CloudRuntimeException(String.format("Veeam REST API %s failed: %s", what, e.getMessage()), e);
        } finally {
            request.releaseConnection();
        }
    }

    private String url(String path, Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return baseUrl + path;
        }
        StringJoiner q = new StringJoiner("&");
        query.forEach((k, v) -> q.add(enc(k) + "=" + enc(v)));
        return baseUrl + path + "?" + q;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
