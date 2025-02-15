/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.build.service.docker.access.hc;

import java.io.File;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jkube.kit.build.service.docker.access.hc.http.HttpRequestException;
import org.eclipse.jkube.kit.build.service.docker.access.hc.util.ClientBuilder;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

public class ApacheHttpClientDelegate {

    private final ClientBuilder clientBuilder;
    private final CloseableHttpClient httpClient;

    public ApacheHttpClientDelegate(ClientBuilder clientBuilder, boolean pooled) throws IOException {
        this.clientBuilder = clientBuilder;
        this.httpClient = pooled ? clientBuilder.buildPooledClient() : clientBuilder.buildBasicClient();
    }

    public CloseableHttpClient createBasicClient()  {
        try {
            return clientBuilder.buildBasicClient();
        } catch (IOException exp) {
            throw new IllegalStateException("Cannot create single HTTP client: " + exp, exp);
        }
    }

    public CloseableHttpClient getHttpClient() {
        return httpClient;
    }

    public void close() throws IOException {
        httpClient.close();
    }

    public int delete(String url, int... statusCodes) throws IOException {
        return delete(url, new StatusCodeResponseHandler(), statusCodes);
    }

    public static class StatusCodeResponseHandler implements ResponseHandler<Integer> {
        @Override
        public Integer handleResponse(HttpResponse response) {
            return response.getStatusLine().getStatusCode();
        }

    }

    public <T> T delete(String url, ResponseHandler<T> responseHandler, int... statusCodes)
        throws IOException {
        return httpClient.execute(newDelete(url),
                                  new StatusCodeCheckerResponseHandler<>(responseHandler,
                                                                         statusCodes));
    }

    public String get(String url, int... statusCodes) throws IOException {
        return httpClient.execute(newGet(url), new StatusCodeCheckerResponseHandler<>(
            new BodyResponseHandler(), statusCodes));
    }

    public <T> T get(String url, ResponseHandler<T> responseHandler, int... statusCodes)
        throws IOException {
        return httpClient
            .execute(newGet(url), new StatusCodeCheckerResponseHandler<>(responseHandler, statusCodes));
    }
    public static class BodyResponseHandler implements ResponseHandler<String> {
        @Override
        public String handleResponse(HttpResponse response)
            throws IOException {
            return getResponseMessage(response);
        }

    }

    private static String getResponseMessage(HttpResponse response) throws IOException {
        return (response.getEntity() == null) ? null
            : EntityUtils.toString(response.getEntity()).trim();
    }

    public <T> T post(String url, Object body, Map<String, String> headers,
                      ResponseHandler<T> responseHandler, int... statusCodes) throws IOException {
        HttpUriRequest request = newPost(url, body);
        for (Entry<String, String> entry : headers.entrySet()) {
            request.addHeader(entry.getKey(), entry.getValue());
        }

        return httpClient.execute(request, new StatusCodeCheckerResponseHandler<>(responseHandler, statusCodes));
    }

    public <T> T post(String url, Object body, ResponseHandler<T> responseHandler,
                      int... statusCodes) throws IOException {
        return httpClient.execute(newPost(url, body),
                                  new StatusCodeCheckerResponseHandler<>(responseHandler,
                                                                         statusCodes));
    }

    public int post(String url, int... statusCodes) throws IOException {
        return post(url, null, new StatusCodeResponseHandler(), statusCodes);
    }

    public int put(String url, Object body, int... statusCodes) throws IOException {
        return httpClient.execute(newPut(url, body),
                                  new StatusCodeCheckerResponseHandler<>(new StatusCodeResponseHandler(), statusCodes));
    }

    // =========================================================================================

    private HttpUriRequest addDefaultHeaders(HttpUriRequest req, Object body) {
        req.addHeader(HttpHeaders.ACCEPT, "*/*");
        if (body instanceof File) {
            req.addHeader(HttpHeaders.CONTENT_TYPE, URLConnection.guessContentTypeFromName(((File)body).getName()));
        }
        if (body != null && !req.containsHeader(HttpHeaders.CONTENT_TYPE)) {
            req.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        }
        return req;
    }

    private HttpUriRequest newDelete(String url) {
        return addDefaultHeaders(new HttpDelete(url), null);
    }

    private HttpUriRequest newGet(String url) {
        return addDefaultHeaders(new HttpGet(url), null);
    }

    private HttpUriRequest newPut(String url, Object body) {
        HttpPut put = new HttpPut(url);
        setEntityIfGiven(put, body);
        return addDefaultHeaders(put, body);
    }

    private HttpUriRequest newPost(String url, Object body) {
        HttpPost post = new HttpPost(url);
        setEntityIfGiven(post, body);
        return addDefaultHeaders(post, body);
    }


    private void setEntityIfGiven(HttpEntityEnclosingRequestBase request, Object entity) {
        if (entity != null) {
            if (entity instanceof File) {
                request.setEntity(new FileEntity((File) entity));
            } else {
                request.setEntity(new StringEntity((String) entity, Charset.defaultCharset()));
            }
        }
    }

    private static class StatusCodeCheckerResponseHandler<T> implements ResponseHandler<T> {

        private final int[] statusCodes;
        private final ResponseHandler<T> delegate;

        StatusCodeCheckerResponseHandler(ResponseHandler<T> delegate, int... statusCodes) {
            this.statusCodes = statusCodes;
            this.delegate = delegate;
        }

        @Override
        public T handleResponse(HttpResponse response) throws IOException {
            StatusLine statusLine = response.getStatusLine();
            int statusCode = statusLine.getStatusCode();
            for (int code : statusCodes) {
                if (statusCode == code) {
                    return delegate.handleResponse(response);
                }
            }

            String reason = statusLine.getReasonPhrase().trim();
            throw new HttpRequestException(String.format("%s (%s: %d)", getResponseMessage(response),
                                                         reason, statusCode));
        }

    }

    public static class BodyAndStatusResponseHandler implements ResponseHandler<HttpBodyAndStatus> {

        @Override
        public HttpBodyAndStatus handleResponse(HttpResponse response)
            throws IOException {
            return new HttpBodyAndStatus(response.getStatusLine().getStatusCode(),
                                         getResponseMessage(response));
        }
    }

    public static class HttpBodyAndStatus {

        private final int statusCode;
        private final String body;

        public HttpBodyAndStatus(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }
}
