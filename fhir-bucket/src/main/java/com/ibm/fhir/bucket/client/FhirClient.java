/*
 * (C) Copyright IBM Corp. 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.fhir.bucket.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.pool.PoolStats;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;

import com.ibm.fhir.database.utils.api.DataAccessException;
import com.ibm.fhir.model.format.Format;
import com.ibm.fhir.model.parser.FHIRParser;
import com.ibm.fhir.model.parser.exception.FHIRParserException;
import com.ibm.fhir.model.resource.Resource;

/**
 * Handles pooled HTTP/S connections to a FHIR server. Derived from the
 * former High Volume Ingestion Tool (HVIT) which is known to scale to
 * a large number of client connections.
 */
public class FhirClient {

    private static final Logger logger = Logger.getLogger(FhirClient.class.getName());
    private static final String USER_AGENT = "FHIR_BUCKET_LOADER";

    // Connection pool managing FHIR server HTTPS connections
    private PoolingHttpClientConnectionManager connManager;
    
    // HTTP client used to POST/PUT/GET FHIR server requests
    private CloseableHttpClient client;
    private String[] enabledCiphers;

    // connection properties encapsulated in an adapter for easy access
    private final ClientPropertyAdapter propertyAdapter;

    // The common headers we use which are shared across all threads
    private final Map<String,String> headers = new ConcurrentHashMap<String, String>();
    
    /**
     * Public constructor
     * @param cpa
     */
    public FhirClient(ClientPropertyAdapter cpa) {
        this.propertyAdapter = cpa;
    }

    /**
     * Add the given key/value as a header
     * @param key
     * @param value
     */
    public void addHeader(String key, String value) {
        this.headers.put(key, value);
    }
    
    /**
     * Initialize the SSL connection pool after all the required field values have been injected
     */
    public void init(String tenantName) {
        if (connManager != null) {
            throw new IllegalStateException("Already initialied");
        }

        
        String enabledCiphersValue = propertyAdapter.getEnabledCiphers();
        if (enabledCiphersValue != null && !enabledCiphersValue.isEmpty()) {
            enabledCiphers = enabledCiphersValue.split(",");
        }

        ConnectionKeepAliveStrategy connKeepAliveStrategy = new ConnectionKeepAliveStrategy() {

            @Override
            public long getKeepAliveDuration(HttpResponse response, HttpContext context) {
                return 60000*60;
            }
        };
        
        try {
            // SSLContext sslContext = SSLContexts.custom().build();
            
            SSLContextBuilder sslContextBuilder = SSLContextBuilder.create();
            // sslContextBuilder.loadKeyMaterial(new File(keystoreFilename), keystorePass.toCharArray(), keyPass.toCharArray());
            sslContextBuilder.loadTrustMaterial(new File(propertyAdapter.getTruststore()), propertyAdapter.getTruststorePass().toCharArray());
            SSLContext sslContext = sslContextBuilder.build();

            // For dev/test setups, allow connections to a FHIR server using a hostname
            // other than localhost
            HostnameVerifier hnv;
            if (propertyAdapter.isDisableHostnameVerification()) {
                hnv = new NoopHostnameVerifier();
            } else {
                hnv = SSLConnectionSocketFactory.getDefaultHostnameVerifier();
            }
            SSLConnectionSocketFactory factory = new SSLConnectionSocketFactory(sslContext, new String[]{"TLSv1.2"}, enabledCiphers, hnv);

            Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.getSocketFactory())
                    .register("https",factory).build();

            connManager = new PoolingHttpClientConnectionManager(registry);
            connManager.setMaxTotal(propertyAdapter.getPoolConnectionsMax());
            connManager.setDefaultMaxPerRoute(propertyAdapter.getPoolConnectionsMax());
            connManager.setValidateAfterInactivity(60000);
            connManager.setDefaultSocketConfig(SocketConfig.custom().build());
            
            client = obtainCloseableHttpClient(connKeepAliveStrategy);
        } 
        catch (KeyManagementException e) {
            throw new IllegalStateException("Failed to initialize connection manager", e);
        } 
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to initialize connection manager", e);
        }        
        catch (IOException x) {
            throw new IllegalStateException("Failed to initialize connection manager", x);
        }
        catch (CertificateException x) {
            throw new IllegalStateException("Failed to initialize connection manager", x);
        }
        catch (KeyStoreException x) {
            throw new IllegalStateException("Failed to initialize connection manager", x);
        }

        if (tenantName != null) {
            this.headers.put(Headers.TENANT_HEADER, tenantName);
        }

        // For now, we only talk JSON with the FHIR server
        this.headers.put(Headers.ACCEPT_HEADER, ContentType.APPLICATION_JSON.getMimeType());
        this.headers.put(Headers.CONTENT_TYPE_HEADER, ContentType.APPLICATION_JSON.getMimeType());
        this.headers.put("Prefer", "return=representation");

        String user = propertyAdapter.getFhirServerUser();
        String pass = propertyAdapter.getFhirServerPass();
        if (user != null && pass != null) {
            // Set up basic auth
            String b64 = Base64.getEncoder().encodeToString(user.concat(":").concat(pass).getBytes());
            headers.put(Headers.AUTH_HEADER, "Basic ".concat(b64));
        }
    }
    
    /**
     * Add our headers to the request
     * @param request
     */
    private void addHeadersTo(final HttpRequestBase request) {
        // inject each header into the request
        headers.entrySet().stream().forEach(e -> request.addHeader(e.getKey(), e.getValue())); 
    }
    
    private String buildTargetPath(String resourceName) {
        StringBuilder result = new StringBuilder();

        result.append("https://");
        result.append(propertyAdapter.fhirServerHost());
        result.append(":");
        result.append(propertyAdapter.fhirServerPort());
        result.append(propertyAdapter.fhirServerEndpoint());
        
        if (resourceName != null) {
            result.append(resourceName);
        }
        
        return result.toString();
    }
    
    public FhirServerResponse get(String url, Function<Reader, Resource> fn) {
        
        String target = buildTargetPath(url);
        if (logger.isLoggable(Level.FINE)){
            logger.fine("REQUEST GET "+ target);
        }

        HttpGet getRequest = new HttpGet(target);
        addHeadersTo(getRequest);
        
        for (int i = 1; ; i++) {
            try {
                long startTime = System.nanoTime();
                HttpResponse response = client.execute(getRequest);
                if(logger.isLoggable(Level.FINE)){
                    Header responseHeaders[] = response.getAllHeaders();
                    
                    StringBuilder msg = new StringBuilder();
                    msg.append("Response HTTP Headers: ");
                    for (Header responseHeader : responseHeaders) {
                        msg.append(System.lineSeparator());
                        msg.append("\t" + responseHeader.getName() + ": " + responseHeader.getValue());
                    }
                    logger.fine(msg.toString());
                }
                return buildResponse(response, startTime, true);
            } catch (NoHttpResponseException e) {
                logger.warning("Encountered an org.apache.http.NoHttpResponseException during GET request. " + e);
                logger.warning("GET URL: "+target);
                logger.warning("Will retry this request for the Nth time. N = " + i);
            } catch (IOException e) {
                logger.severe("Error while executing the GET request. " + e);
                logger.warning("GET URL: "+target);
                logger.warning("Skipping this request.");
                return null;
            }
        }        
    }
    
    /**
     * Issue a POST request at the given url
     * @param sUrl
     * @param body
     * @return
     */
    public FhirServerResponse post(String url, String body) {
        String target = buildTargetPath(url);
        if(logger.isLoggable(Level.FINE)) {
            logger.fine("REQUEST POST "+ target);
        }
        
        try {
            HttpPost postRequest = new HttpPost(target);
            postRequest.setEntity(new StringEntity(body));
            addHeadersTo(postRequest);
            
            if(logger.isLoggable(Level.FINE)) {
                logger.fine("REQUEST POST BODY - " + body);
            }
            
            long startTime = System.currentTimeMillis();
            HttpResponse response = client.execute(postRequest);
            
            // Log details of the response if required
            if(logger.isLoggable(Level.FINE)) {
                Header responseHeaders[] = response.getAllHeaders();
                
                StringBuilder msg = new StringBuilder();
                msg.append("Response HTTP Headers: ");
                for (Header responseHeader : responseHeaders) {
                    msg.append(System.lineSeparator());
                    msg.append("\t" + responseHeader.getName() + ": " + responseHeader.getValue());
                }
                logger.fine(msg.toString());
            }

            // If we are posting a bundle, then we need to parse the response entity
            boolean isBundle = url.isEmpty();
            return buildResponse(response, startTime, isBundle);
            
        } catch (UnsupportedEncodingException e) {
            logger.severe("Can't encode json string into entity. "+e);
            logger.warning("POST URL: "+target+"\nRequest Body: "+body);
            throw new IllegalStateException("FHIR client configuration error");
        } catch (ClientProtocolException e) {
            logger.severe("Error while executing the POST request. "+e);
            logger.warning("POST URL: "+target+"\nRequest Body: "+body);
            throw new DataAccessException("FHIR server connection failed");
        } catch (IOException e) {
            logger.severe("Error while executing the POST request. "+e);
            logger.warning("POST URL: "+target+"\nRequest Body: "+body);
            throw new DataAccessException("FHIR server connection failed");
        }
    }
    
    public FhirServerResponse put(String url, Map<String, String> headers, String body) {
        String target = buildTargetPath(url);

        if (logger.isLoggable(Level.FINE)) {
            logger.fine("REQUEST PUT "+target);
        }
        
        try {
            HttpPut putRequest = new HttpPut(target);
            putRequest.setEntity(new StringEntity(body));
            addHeadersTo(putRequest);
            
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("REQUEST PUT BODY - " + body);
            }
            
            long startTime = System.currentTimeMillis();
            HttpResponse response = client.execute(putRequest);
            if (logger.isLoggable(Level.FINE)) {
                Header responseHeaders[] = response.getAllHeaders();
                logger.fine("Response HTTP Headers: ");
                for (Header responseHeader : responseHeaders) {
                    logger.fine("\t" + responseHeader.getName() + ": " + responseHeader.getValue());
                }
            }
            return buildResponse(response, startTime, false);
            
        } catch (UnsupportedEncodingException e) {
            logger.severe("Can't encode json string into entity. "+e);
            logger.warning("PUT URL: "+target+"\nRequest Body: "+body);
        } catch (ClientProtocolException e) {
            logger.severe("Error while executing the PUT request. "+e);
            logger.warning("PUT URL: "+target+"\nRequest Body: "+body);
        } catch (IOException e) {
            logger.severe("Error while executing the PUT request. "+e);
            logger.warning("PUT URL: "+target+"\nRequest Body: "+body);
        }
        
        return null;
    }
    
    public void shutdown() {
        if (client != null) {
            try {
                connManager.shutdown();
                client.close();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to shutdown HTTP clients and Connection Manager successfully. ", e);
            }
        }
        
    }

    /**
     * Get statistics from the internal HTTP connection manager
     * @return
     */
    public PoolStats getPoolInformation() {
        if (connManager != null) {
            return connManager.getTotalStats();
        } 
        
        return null;
    }

    /**
     * Construct a FhirServerResponse from the FHIR server {@link HttpResponse}
     * @param response
     * @param startTime
     * @return
     */
    private FhirServerResponse buildResponse(HttpResponse response, long startTime, boolean processResponseEntity) {
        FhirServerResponse sr = new FhirServerResponse();
        
        int status = response.getStatusLine().getStatusCode();
        sr.setStatusCode(status);
        sr.setStatusMessage(response.getStatusLine().getReasonPhrase());

        HttpEntity entity = response.getEntity();
        try {
            if (status == HttpStatus.SC_OK || status == HttpStatus.SC_CREATED) {
                if (processResponseEntity) {
                    processEntity(sr, entity);
                } else if (response.getFirstHeader("Location") != null) {
                    // Single resource case, no response body, just the URL returned in the Location header
                    sr.setLocationHeader(response.getFirstHeader("Location").getValue());
                } else {
                    logger.warning("No body or Location header in response");
                }
            } else if (status == HttpStatus.SC_BAD_REQUEST) {
                processOperationalOutcome(sr, entity);
            } else {
                logger.warning("Unexpected server response: " + status + " " + response.getStatusLine().getReasonPhrase());
            }
        } finally {
            consume(entity);
            long endTime = System.nanoTime();
            sr.setResponseTime((int)(endTime-startTime));
        }
        
            // Last-Modified: 2018-11-26T05:07:00.954Z
            // TODO
//            Header lastModifiedHeader = response.getFirstHeader("Last-Modified");
//            if (lastModifiedHeader != null) {
//                sr.setLastModified(TimeUtil.getFhirTime(lastModifiedHeader.getValue()));
//            }
//            else {
//                // TODO. Must have a lastModified, but we don't know what it is
//                sr.setLastModified(new Date(0));
//            }
            
                    
        return sr;
    }
    
    /**
     * Extract the operational outcome message from the response entity
     * @param sr
     * @param entity
     */
    protected void processOperationalOutcome(FhirServerResponse sr, HttpEntity entity) {
        // simply consume the message as a string
        try {
            sr.setOperationalOutcomeText(EntityUtils.toString(entity));
        } catch (IOException x) {
            logger.severe("IO error reading response entity: " + x.getMessage());
            sr.setOperationalOutcomeText(x.getMessage());
        }
    }

    /**
     * Consume any remaining content from the entity so that the connection
     * can be reused
     * @param entity
     */
    private void consume(HttpEntity entity) {
        if (entity != null) {
            try {
                EntityUtils.consume(entity);
            } catch (IOException x) {
                logger.warning("consume(entity) failed: " + x.getMessage());
            }
        }
    }

    
    /**
     * Overridden by child classes for specialized behavior.
     * @param connKeepAliveStrategy
     */
    protected CloseableHttpClient obtainCloseableHttpClient(ConnectionKeepAliveStrategy connKeepAliveStrategy) {
        return HttpClients.custom().setConnectionReuseStrategy(DefaultConnectionReuseStrategy.INSTANCE)
                .setUserAgent(USER_AGENT).setKeepAliveStrategy(connKeepAliveStrategy)
                .setConnectionManager(connManager).setRetryHandler(new DefaultHttpRequestRetryHandler(1,true))
                .setUserTokenHandler(new UserTokenHandler() {
                    @Override
                    public Object getUserToken(HttpContext context) {
                        return null;
                    }        
                })
                .build();
    }
    

    /**
     * Parse the response returned by the FHIR server
     * @param rdr
     * @return
     */
    private void processEntity(FhirServerResponse sr, HttpEntity entity) {

        try {
            try (InputStream instream = entity.getContent()) {
                Resource resource = FHIRParser.parser(Format.JSON).parse(new InputStreamReader(instream, StandardCharsets.UTF_8));
                sr.setResource(resource);
            } catch (FHIRParserException e) {
                throw new IllegalStateException(e);
            } finally {
            }
        } catch (IOException x) {
            throw new IllegalStateException(x);
        }
    }
    
    /**
     * @return the URL to the base of the FHIR server
     */
    public String getBaseUrl() {
        return buildTargetPath(null);
    }
}
