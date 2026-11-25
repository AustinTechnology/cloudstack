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
import org.apache.http.client.methods.HttpDelete;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

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
        public int restorePointsCount;
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
            o.restorePointsCount = n.path("restorePointsCount").asInt(0);
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

    /** Restores a Hyper-V VM over itself (original location), powered off. */
    public Session restoreHyperVVmInPlace(String restorePointId, String reason) {
        ObjectNode body = MAPPER.createObjectNode().put("restorePointId", restorePointId).put("type", "OriginalLocation")
                .put("powerUp", false).put("reason", reason);
        return session(send(new HttpPost(url("/api/v1/restore/vmRestore/hyperV", null)), body.toString()));
    }

    // ---- Veeam Plug-in for Proxmox VE ----
    // The public REST API cannot restore Proxmox VMs. The plug-in's own API (the one the Veeam Web UI uses, behind
    // the same token) can. It is not documented for third parties, so only the restore and its session are used.

    /** The plug-in's id on every Veeam server (also the platformId of Proxmox backup objects). */
    public static final String PROXMOX_PLUGIN = "/extension/01208ee8-47fe-4ea8-8727-5115874da1ad/api/v1";

    /**
     * Restores a Proxmox VM over itself (same VMID, disks and configuration as in the restore point), powered off.
     * sourceVmId is "&lt;SMBIOS UUID&gt;:&lt;VMID&gt;"; restorePointId is the public REST API's restore point id.
     */
    public Session restoreProxmoxVmInPlace(String sourceVmId, String restorePointId, String reason) {
        ObjectNode body = MAPPER.createObjectNode().put("sourceVmId", sourceVmId).put("restoreToOriginal", true)
                .put("restorePointId", restorePointId).put("powerOnVmAfterRestore", false).put("reason", reason);
        Session s = new Session();
        s.id = text(send(new HttpPost(url(PROXMOX_PLUGIN + "/restorePoints/restore", null)), body.toString()), "sessionId");
        if (StringUtils.isBlank(s.id)) {
            throw new CloudRuntimeException("The Veeam Proxmox plug-in started no restore session");
        }
        return s;
    }

    /** A plug-in session: state Finished when done, result Success, Warning or Failed, message its last warning or error. */
    public Session getProxmoxSession(String sessionId) {
        JsonNode n = send(new HttpGet(url(PROXMOX_PLUGIN + "/sessions/" + enc(sessionId), null)), null);
        Session s = new Session();
        s.id = sessionId; s.state = text(n, "state"); s.result = text(n, "status");
        for (JsonNode event : n.path("progressState").path("events")) {
            if (!"Information".equalsIgnoreCase(text(event, "severity"))) {
                s.message = text(event, "message");
            }
        }
        return s;
    }

    /** The plug-in job whose description holds this text (an instance's UUID), or null. */
    public JsonNode findProxmoxJobByDescription(String text) {
        JsonNode page = send(new HttpGet(url(PROXMOX_PLUGIN + "/jobs", Map.of("limit", "1000", "offset", "0"))), null);
        for (JsonNode job : page.path("results")) {
            if (StringUtils.contains(text(job, "description"), text)) {
                return job;
            }
        }
        return null;
    }

    /** The plug-in's jobs (with their full settings), or the one with this exact name; null when none has it. */
    public JsonNode findProxmoxJobByName(String name) {
        JsonNode page = send(new HttpGet(url(PROXMOX_PLUGIN + "/jobs", Map.of("limit", "1000", "offset", "0"))), null);
        for (JsonNode job : page.path("results")) {
            if (name.equals(text(job, "name"))) {
                return job;
            }
        }
        return null;
    }

    /** Clones a plug-in job: Veeam names the copy "&lt;name&gt;_clone1" and leaves it disabled. Returns the new job's id. */
    public String cloneProxmoxJob(String jobId) {
        String id = text(send(new HttpPost(url(PROXMOX_PLUGIN + "/jobs/" + enc(jobId) + "/clone", null)), "{}"), "id");
        if (StringUtils.isBlank(id)) {
            throw new CloudRuntimeException("The Veeam Proxmox plug-in returned no id for the cloned job");
        }
        return id;
    }

    public ObjectNode getProxmoxJobSettings(String jobId) {
        return (ObjectNode) send(new HttpGet(url(PROXMOX_PLUGIN + "/jobs/" + enc(jobId) + "/settings", null)), null);
    }

    /** Replaces a plug-in job's settings. Veeam refuses the GFS begin times it returns, so they are left out. Re-enables the job. */
    public void putProxmoxJobSettings(String jobId, ObjectNode settings) {
        for (JsonNode period : settings.path("gfsSettings")) {
            if (period instanceof ObjectNode) {
                ((ObjectNode) period).remove("beginTimeUtc");
            }
        }
        send(new HttpPut(url(PROXMOX_PLUGIN + "/jobs/" + enc(jobId) + "/settings", null)), settings.toString());
    }

    public void enableProxmoxJob(String jobId) {
        send(new HttpPost(url(PROXMOX_PLUGIN + "/jobs/" + enc(jobId) + "/enable", null)), "{}");
    }

    /** Deletes a plug-in job. Its backups stay in the repository. */
    public void deleteProxmoxJob(String jobId) {
        send(new HttpDelete(url(PROXMOX_PLUGIN + "/jobs/" + enc(jobId), null)), null);
    }

    /** The plug-in's inventory object of a Proxmox VM (id "&lt;SMBIOS UUID&gt;:&lt;VMID&gt;"), as a job includes it; null if Veeam does not list it. */
    public ObjectNode findProxmoxVm(String uuid, String vmid) {
        String wanted = uuid.toLowerCase() + ":" + vmid;
        JsonNode page = send(new HttpGet(url(PROXMOX_PLUGIN + "/inventory", Map.of("limit", "10000", "offset", "0"))), null);
        for (JsonNode o : page.path("results")) {
            if ("VirtualMachine".equals(text(o, "type")) && wanted.equalsIgnoreCase(text(o, "id"))) {
                ObjectNode vm = MAPPER.createObjectNode();
                for (String field : new String[]{"id", "clusterId", "type", "name"}) {
                    vm.set(field, o.get(field));
                }
                return vm;
            }
        }
        return null;
    }

    /** Asks Veeam to read the Proxmox inventory again (a new or moved VM is not in it until then). */
    public void rescanProxmoxInventory() {
        send(new HttpPost(url(PROXMOX_PLUGIN + "/inventory/rescan", null)), "{}");
    }

    /** What the restore point holds: name, node (subClusterId) and disks (id, busType, index, size, storageContainerId). */
    public JsonNode getProxmoxRestorePointMetadata(String restorePointId) {
        return send(new HttpGet(url(PROXMOX_PLUGIN + "/restorePoints/" + enc(restorePointId) + "/metadata", null)), null);
    }

    /** The Proxmox nodes of the cluster a Veeam cluster id names (id, name). */
    public JsonNode listProxmoxNodes(String clusterId) {
        return results(send(new HttpGet(url(PROXMOX_PLUGIN + "/proxmox/clusters/" + enc(clusterId) + "/nodes", null)), null));
    }

    /** Storage of a node that Instant Recovery can run disks from (directory-type storage holding qcow2): id, freeSpace. */
    public JsonNode listProxmoxInstantRecoveryStorage(String nodeId) {
        return results(send(new HttpGet(url(PROXMOX_PLUGIN + "/proxmox/clusters/" + enc(nodeId) + "/instantRecoveryCompatibleStorageContainers", null)), null));
    }

    /** Starts an Instant Recovery; returns the id of its restore session, which the migration and stop calls take. */
    public String startProxmoxInstantRecovery(ObjectNode body) {
        String id = text(send(new HttpPost(url(PROXMOX_PLUGIN + "/instantRecovery", null)), body.toString()), "sessionId");
        if (StringUtils.isBlank(id)) {
            throw new CloudRuntimeException("The Veeam Proxmox plug-in started no Instant Recovery session");
        }
        return id;
    }

    /** An Instant Recovery session: state Running while the VM runs from the backup; status/result. */
    public Session getProxmoxInstantRecovery(String sessionId) {
        return pluginSession(send(new HttpGet(url(PROXMOX_PLUGIN + "/instantRecovery/sessions/" + enc(sessionId), null)), null));
    }

    /** Starts copying an Instant Recovery VM's disks off the backup (Veeam's migration); returns the migration's id. Can be retried. */
    public String startProxmoxInstantRecoveryMigration(String sessionId) {
        String id = text(send(new HttpPost(url(PROXMOX_PLUGIN + "/instantRecovery/sessions/" + enc(sessionId) + "/startMigration", null)), "{}"), "sessionId");
        if (StringUtils.isBlank(id)) {
            throw new CloudRuntimeException("The Veeam Proxmox plug-in started no migration");
        }
        return id;
    }

    /** A migration: state Running, then Finished with result Success or Failed. */
    public Session getProxmoxInstantRecoveryMigration(String migrationId) {
        return pluginSession(send(new HttpGet(url(PROXMOX_PLUGIN + "/instantRecovery/migrations/" + enc(migrationId), null)), null));
    }

    /** The plug-in answers lists as {"results": [...]}; some calls as a bare array. */
    private static JsonNode results(JsonNode n) {
        return n.has("results") ? n.get("results") : n;
    }

    private Session pluginSession(JsonNode n) {
        Session s = new Session();
        s.id = text(n, "id"); s.state = text(n, "state"); s.result = text(n, "result");
        s.message = "Success".equalsIgnoreCase(text(n, "status")) || "Running".equalsIgnoreCase(text(n, "status")) ? null : text(n, "status");
        return s;
    }

    // ---- Hyper-V Instant Recovery (public REST) ----

    /** Starts Instant Recovery of a Hyper-V VM over itself, powered on; returns the mount (id, sessionId, state). */
    public JsonNode startHyperVInstantRecovery(String restorePointId, String reason) {
        ObjectNode body = MAPPER.createObjectNode().put("restorePointId", restorePointId).put("type", "OriginalLocation")
                .put("powerUp", true).put("reason", reason);
        body.putObject("secureRestore").put("antivirusScanEnabled", false);
        return send(new HttpPost(url("/api/v1/restore/instantRecovery/hyperV/vm", null)), body.toString());
    }

    /** A Hyper-V Instant Recovery mount: state Mounting, Mounted, Dismounting or Failed; null once Veeam no longer lists it. */
    public JsonNode getHyperVInstantRecovery(String mountId) {
        try {
            return send(new HttpGet(url("/api/v1/restore/instantRecovery/hyperV/vm/" + enc(mountId), null)), null);
        } catch (CloudRuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return null;
            }
            throw e;
        }
    }

    /** Moves a Hyper-V Instant Recovery VM to production storage (its original location); Veeam then removes the mount. */
    public void migrateHyperVInstantRecovery(String mountId) {
        send(new HttpPost(url("/api/v1/restore/instantRecovery/hyperV/vm/" + enc(mountId) + "/migrate", null)), "{}");
    }

    // ---- Hyper-V jobs (public REST) ----

    /** Clones a job; returns the copy as Veeam returns it (disabled, named after the original). */
    public JsonNode cloneJob(String jobId) {
        return send(new HttpPost(url("/api/v1/jobs/" + enc(jobId) + "/clone", null)), "{}");
    }

    /** Deletes a job. Its backups stay in the repository. */
    public void deleteJob(String jobId) {
        send(new HttpDelete(url("/api/v1/jobs/" + enc(jobId), null)), null);
    }

    public void enableJob(String jobId) {
        send(new HttpPost(url("/api/v1/jobs/" + enc(jobId) + "/enable", null)), "{}");
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
                // Public REST errors carry "message"; the plug-ins' (RFC 9110 problem details) carry "detail" and "title".
                JsonNode error = StringUtils.isBlank(body) ? MAPPER.createObjectNode() : MAPPER.readTree(body);
                String message = StringUtils.firstNonBlank(error.path("message").asText(""), error.path("detail").asText(""), error.path("title").asText(""), "");
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
