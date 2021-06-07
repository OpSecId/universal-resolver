package uniresolver.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uniresolver.ResolutionException;
import uniresolver.UniResolver;
import uniresolver.result.ResolveResult;
import uniresolver.util.HttpBindingUtil;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ClientUniResolver implements UniResolver {

	private static Logger log = LoggerFactory.getLogger(ClientUniResolver.class);

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private HttpClient httpClient = HttpClients.createDefault();
	private URI resolveUri = URI.create("http://localhost:8080/1.0/identifiers");
	private URI propertiesUri = URI.create("http://localhost:8080/1.0/properties");
	private URI methodsUri = URI.create("http://localhost:8080/1.0/methods");
	private URI testIdentifiersUri = URI.create("http://localhost:8080/1.0/testIdentifiers");

	public ClientUniResolver() {

	}

	public static ClientUniResolver create(URI baseUri) {

		if (! baseUri.toString().endsWith("/")) baseUri = URI.create(baseUri.toString() + "/");

		ClientUniResolver clientUniResolver = new ClientUniResolver();
		clientUniResolver.setResolveUri(URI.create(baseUri.toString() + "identifiers"));
		clientUniResolver.setPropertiesUri(URI.create(baseUri.toString() + "properties"));
		clientUniResolver.setMethodsUri(URI.create(baseUri.toString() + "methods"));
		clientUniResolver.setTestIdentifiersUri(URI.create(baseUri.toString() + "testIdentifiers"));

		return clientUniResolver;
	}

	@Override
	public ResolveResult resolveRepresentation(String didString, Map<String, Object> resolutionOptions) throws ResolutionException {

		if (didString == null) throw new NullPointerException();

		// URL-encode DID

		String urlEncodedDid = URLEncoder.encode(didString, StandardCharsets.UTF_8);

		// set HTTP URI

		String uriString = this.getResolveUri().toString();
		if (! uriString.endsWith("/")) uriString += "/";
		uriString += urlEncodedDid;

		// set Accept header

		String accept = (String) resolutionOptions.get("accept");
		if (accept == null) throw new ResolutionException("No 'accept' provided in 'resolutionOptions' for resolveRepresentation().");

		List<String> acceptMediaTypes = Arrays.asList(ResolveResult.MEDIA_TYPE, accept);
		String acceptMediaTypesString = String.join(",", acceptMediaTypes);

		// prepare HTTP request

		HttpGet httpGet = new HttpGet(URI.create(uriString));
		httpGet.addHeader("Accept", acceptMediaTypesString);

		// execute HTTP request

		ResolveResult resolveResult = null;

		if (log.isDebugEnabled()) log.debug("Request for DID " + didString + " to: " + uriString);

		try (CloseableHttpResponse httpResponse = (CloseableHttpResponse) this.getHttpClient().execute(httpGet)) {

			// execute HTTP request

			HttpEntity httpEntity = httpResponse.getEntity();
			int statusCode = httpResponse.getStatusLine().getStatusCode();
			String statusMessage = httpResponse.getStatusLine().getReasonPhrase();
			ContentType contentType = ContentType.get(httpResponse.getEntity());
			Charset charset = contentType != null ? contentType.getCharset() : HTTP.DEF_CONTENT_CHARSET;

			if (log.isDebugEnabled()) log.debug("Response status from " + uriString + ": " + statusCode + " " + statusMessage);
			if (log.isDebugEnabled()) log.debug("Response content type from " + uriString + ": " + contentType + " / " + charset);

			// read result

			byte[] httpBodyBytes = EntityUtils.toByteArray(httpEntity);
			String httpBodyString = new String(httpBodyBytes, charset);
			EntityUtils.consume(httpEntity);

			if (log.isDebugEnabled()) log.debug("Response body from " + uriString + ": " + httpBodyString);

			if (contentType != null && isResolveContentType(contentType)) {
				resolveResult = HttpBindingUtil.fromHttpBodyResolveResult(httpBodyString);
			}

			if (statusCode != 200 && resolveResult == null) {
				throw new ResolutionException("Cannot retrieve result for " + didString + ": " + statusCode + " " + statusMessage + " (" + httpBodyString + ")");
			}

			if (resolveResult != null && resolveResult.isErrorResult()) {
				throw new ResolutionException(resolveResult);
			}

			if (resolveResult == null) {
				resolveResult = HttpBindingUtil.fromHttpBodyDidDocument(httpBodyBytes, contentType);
			}
		} catch (ResolutionException ex) {

			throw ex;
		} catch (Exception ex) {

			throw new ResolutionException("Cannot retrieve resolve result for " + didString + " from " + uriString + ": " + ex.getMessage(), ex);
		}

		if (log.isDebugEnabled()) log.debug("Retrieved resolve result for " + didString + " (" + uriString + "): " + resolveResult);

		// done

		return resolveResult;
	}

	@Override
	public Map<String, Map<String, Object>> properties() throws ResolutionException {

		// prepare HTTP request

		String uriString = this.getPropertiesUri().toString();

		HttpGet httpGet = new HttpGet(URI.create(uriString));
		httpGet.addHeader("Accept", UniResolver.PROPERTIES_MIME_TYPE);

		// execute HTTP request

		Map<String, Map<String, Object>> properties;

		if (log.isDebugEnabled()) log.debug("Request to: " + uriString);

		try (CloseableHttpResponse httpResponse = (CloseableHttpResponse) this.getHttpClient().execute(httpGet)) {

			int statusCode = httpResponse.getStatusLine().getStatusCode();
			String statusMessage = httpResponse.getStatusLine().getReasonPhrase();

			if (log.isDebugEnabled()) log.debug("Response status from " + uriString + ": " + statusCode + " " + statusMessage);

			if (httpResponse.getStatusLine().getStatusCode() == 404) return null;

			HttpEntity httpEntity = httpResponse.getEntity();
			String httpBody = EntityUtils.toString(httpEntity);
			EntityUtils.consume(httpEntity);

			if (log.isDebugEnabled()) log.debug("Response body from " + uriString + ": " + httpBody);

			if (httpResponse.getStatusLine().getStatusCode() > 200) {

				if (log.isWarnEnabled()) log.warn("Cannot retrieve PROPERTIES from " + uriString + ": " + httpBody);
				throw new ResolutionException(httpBody);
			}

			properties = (Map<String, Map<String, Object>>) objectMapper.readValue(httpBody, LinkedHashMap.class);
		} catch (IOException ex) {

			throw new ResolutionException("Cannot retrieve PROPERTIES from " + uriString + ": " + ex.getMessage(), ex);
		}

		if (log.isDebugEnabled()) log.debug("Retrieved PROPERTIES (" + uriString + "): " + properties);

		// done

		return properties;
	}

	@Override
	public Set<String> methods() throws ResolutionException {

		// prepare HTTP request

		String uriString = this.getMethodsUri().toString();

		HttpGet httpGet = new HttpGet(URI.create(uriString));
		httpGet.addHeader("Accept", UniResolver.METHODS_MIME_TYPE);

		// execute HTTP request

		Set<String> methods;

		if (log.isDebugEnabled()) log.debug("Request to: " + uriString);

		try (CloseableHttpResponse httpResponse = (CloseableHttpResponse) this.getHttpClient().execute(httpGet)) {

			int statusCode = httpResponse.getStatusLine().getStatusCode();
			String statusMessage = httpResponse.getStatusLine().getReasonPhrase();

			if (log.isDebugEnabled()) log.debug("Response status from " + uriString + ": " + statusCode + " " + statusMessage);

			if (httpResponse.getStatusLine().getStatusCode() == 404) return null;

			HttpEntity httpEntity = httpResponse.getEntity();
			String httpBody = EntityUtils.toString(httpEntity);
			EntityUtils.consume(httpEntity);

			if (log.isDebugEnabled()) log.debug("Response body from " + uriString + ": " + httpBody);

			if (httpResponse.getStatusLine().getStatusCode() > 200) {

				if (log.isWarnEnabled()) log.warn("Cannot retrieve METHODS from " + uriString + ": " + httpBody);
				throw new ResolutionException(httpBody);
			}

			methods = (Set<String>) objectMapper.readValue(httpBody, LinkedHashSet.class);
		} catch (IOException ex) {

			throw new ResolutionException("Cannot retrieve METHODS from " + uriString + ": " + ex.getMessage(), ex);
		}

		if (log.isDebugEnabled()) log.debug("Retrieved METHODS (" + uriString + "): " + methods);

		// done

		return methods;
	}

	@Override
	public Map<String, List<String>> testIdentifiers() throws ResolutionException {

		// prepare HTTP request

		String uriString = this.getTestIdentifiersUri().toString();

		HttpGet httpGet = new HttpGet(URI.create(uriString));
		httpGet.addHeader("Accept", UniResolver.TEST_IDENTIFIER_MIME_TYPE);

		// execute HTTP request

		Map<String, List<String>> testIdentifiers;

		if (log.isDebugEnabled()) log.debug("Request to: " + uriString);

		try (CloseableHttpResponse httpResponse = (CloseableHttpResponse) this.getHttpClient().execute(httpGet)) {

			int statusCode = httpResponse.getStatusLine().getStatusCode();
			String statusMessage = httpResponse.getStatusLine().getReasonPhrase();

			if (log.isDebugEnabled()) log.debug("Response status from " + uriString + ": " + statusCode + " " + statusMessage);

			if (httpResponse.getStatusLine().getStatusCode() == 404) return null;

			HttpEntity httpEntity = httpResponse.getEntity();
			String httpBody = EntityUtils.toString(httpEntity);
			EntityUtils.consume(httpEntity);

			if (log.isDebugEnabled()) log.debug("Response body from " + uriString + ": " + httpBody);

			if (httpResponse.getStatusLine().getStatusCode() > 200) {

				if (log.isWarnEnabled()) log.warn("Cannot retrieve TEST IDENTIFIERS from " + uriString + ": " + httpBody);
				throw new ResolutionException(httpBody);
			}

			testIdentifiers = (Map<String, List<String>>) objectMapper.readValue(httpBody, LinkedHashMap.class);
		} catch (IOException ex) {

			throw new ResolutionException("Cannot retrieve TEST IDENTIFIERS from " + uriString + ": " + ex.getMessage(), ex);
		}

		if (log.isDebugEnabled()) log.debug("Retrieved TEST IDENTIFIERS (" + uriString + "): " + testIdentifiers);

		// done

		return testIdentifiers;
	}

	/*
	 * Helper methods
	 */

	private static final ContentType RESOLVE_RESULT_CONTENT_TYPE = ContentType.parse(ResolveResult.MEDIA_TYPE);

	private static boolean isResolveContentType(ContentType contentType) {
		return RESOLVE_RESULT_CONTENT_TYPE.getMimeType().equals(contentType.getMimeType()) && RESOLVE_RESULT_CONTENT_TYPE.getParameter("profile").equals(contentType.getParameter("profile"));
	}

	/*
	 * Getters and setters
	 */

	public HttpClient getHttpClient() {

		return this.httpClient;
	}

	public void setHttpClient(HttpClient httpClient) {

		this.httpClient = httpClient;
	}

	public URI getResolveUri() {

		return this.resolveUri;
	}

	public void setResolveUri(URI resolveUri) {

		this.resolveUri = resolveUri;
	}

	public void setResolveUri(String resolveUri) {

		this.resolveUri = URI.create(resolveUri);
	}

	public URI getPropertiesUri() {

		return this.propertiesUri;
	}

	public void setPropertiesUri(URI propertiesUri) {

		this.propertiesUri = propertiesUri;
	}

	public void setPropertiesUri(String propertiesUri) {

		this.propertiesUri = URI.create(propertiesUri);
	}

	public URI getMethodsUri() {

		return this.methodsUri;
	}

	public void setMethodsUri(URI methodsUri) {

		this.methodsUri = methodsUri;
	}

	public URI getTestIdentifiersUri() {

		return this.testIdentifiersUri;
	}

	public void setTestIdentifiersUri(URI testIdentifiersUri) {

		this.testIdentifiersUri = testIdentifiersUri;
	}
}
