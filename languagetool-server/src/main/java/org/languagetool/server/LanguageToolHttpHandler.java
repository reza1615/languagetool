/* LanguageTool, a natural language style checker
 * Copyright (C) 2011 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.server;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

import org.apache.tika.language.LanguageIdentifier;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.gui.Configuration;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.bitext.BitextRule;
import org.languagetool.tools.RuleAsXmlSerializer;
import org.languagetool.tools.StringTools;
import org.languagetool.tools.Tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import static org.apache.commons.lang.StringEscapeUtils.escapeXml;

class LanguageToolHttpHandler implements HttpHandler {

  private static final String CONTENT_TYPE_VALUE = "text/xml; charset=UTF-8";
  private static final String ENCODING = "utf-8";
  private static final int CONTEXT_SIZE = 40; // characters
  private static final int MIN_LENGTH_FOR_AUTO_DETECTION = 60;  // characters

  private final Set<String> allowedIps;  
  private final boolean verbose;
  private final boolean internalServer;
  private final RequestLimiter requestLimiter;
  private final LinkedBlockingQueue<Runnable> workQueue;
  private final ExecutorService executorService;

  private long maxCheckTimeMillis = -1;
  private int maxTextLength = Integer.MAX_VALUE;
  private String allowOriginUrl;
  private boolean afterTheDeadlineMode;
  private Language afterTheDeadlineLanguage;
  
  private static int handleCount = 0;

  /**
   * Create an instance. Call {@link #shutdown()} when done.
   * @param verbose print the input text in case of exceptions
   * @param allowedIps set of IPs that may connect or <tt>null</tt> to allow any IP
   * @param requestLimiter may be null
   */
  LanguageToolHttpHandler(boolean verbose, Set<String> allowedIps, boolean internal, RequestLimiter requestLimiter, LinkedBlockingQueue<Runnable> workQueue) {
    this.verbose = verbose;
    this.allowedIps = allowedIps;
    this.internalServer = internal;
    this.requestLimiter = requestLimiter;
    this.workQueue = workQueue;
    this.executorService = Executors.newCachedThreadPool();
  }

  /** @since 2.6 */
  void shutdown() {
    executorService.shutdownNow();
  }

  void setMaxTextLength(int maxTextLength) {
    this.maxTextLength = maxTextLength;
  }

  /**
   * Maximum time allowed per check in milliseconds. If the checking takes longer, it will stop with
   * an exception. Use {@code -1} for no limit.
   * @since 2.6
   */
  void setMaxCheckTimeMillis(long maxCheckTimeMillis) {
    this.maxCheckTimeMillis = maxCheckTimeMillis;
  }

  /**
   * Value to set as the "Access-Control-Allow-Origin" http header. Use {@code null}
   * to not return that header at all. Use {@code *} to run a server that any other web site
   * can use from Javascript/Ajax (search Cross-origin resource sharing (CORS) for details).
   */
  void setAllowOriginUrl(String allowOriginUrl) {
    this.allowOriginUrl = allowOriginUrl;
  }

  /** @since 2.7 */
  void setAfterTheDeadlineMode(Language defaultLanguage) {
    System.out.println("Running in After the Deadline mode, default language: " + defaultLanguage);
    this.afterTheDeadlineMode = true;
    this.afterTheDeadlineLanguage = defaultLanguage;
  }

  @Override
  public void handle(HttpExchange httpExchange) throws IOException {
    synchronized (this) {
      handleCount++;
    }
    String text = null;
    try {
      final URI requestedUri = httpExchange.getRequestURI();
      final String remoteAddress = httpExchange.getRemoteAddress().getAddress().getHostAddress();
      // According to the Javadoc, "Closing an exchange without consuming all of the request body is
      // not an error but may make the underlying TCP connection unusable for following exchanges.",
      // so we consume the request now, even before checking for request limits:
      final Map<String, String> parameters = getRequestQuery(httpExchange, requestedUri);
      if (requestLimiter != null && !requestLimiter.isAccessOkay(remoteAddress)) {
        final String errorMessage = "Error: Access from " + StringTools.escapeXML(remoteAddress) +
                " denied - too many requests. Allowed maximum requests: " + requestLimiter.getRequestLimit() +
                " requests per " + requestLimiter.getRequestLimitPeriodInSeconds() + " seconds";
        sendError(httpExchange, HttpURLConnection.HTTP_FORBIDDEN, errorMessage);
        print(errorMessage);
        return;
      }
      if (allowedIps == null || allowedIps.contains(remoteAddress)) {
        if (requestedUri.getRawPath().endsWith("/Languages")) {
          // request type: list known languages
          printListOfLanguages(httpExchange);
        } else {
          // request type: text checking
          if (afterTheDeadlineMode) {
            text = parameters.get("data");
            if (text == null) {
              throw new IllegalArgumentException("Missing 'data' parameter");
            }
            text = text.replaceAll("</p>", "\n\n").replaceAll("<.*?>", "");  // clean up HTML, position changes don't matter for AtD
          } else {
            text = parameters.get("text");
            if (text == null) {
              throw new IllegalArgumentException("Missing 'text' parameter");
            }
          }
          checkText(text, httpExchange, parameters);
        }
      } else {
        final String errorMessage = "Error: Access from " + StringTools.escapeXML(remoteAddress) + " denied";
        sendError(httpExchange, HttpURLConnection.HTTP_FORBIDDEN, errorMessage);
        throw new RuntimeException(errorMessage);
      }
    } catch (Exception e) {
      print("An error has occurred. Stacktrace follows:", System.err);
      if (verbose) {
        print("Exception was caused by this text: " + text, System.err);
      }
      //noinspection CallToPrintStackTrace
      e.printStackTrace();
      String response;
      if (e.getCause() != null && e.getCause() instanceof TimeoutException) {
        response = "Checking took longer than " + maxCheckTimeMillis/1000 + " seconds, which is this server's limit. " +
                "Please make sure you have selected the proper language or consider submitting a shorter text.";
      } else {
        response = Tools.getFullStackTrace(e);
      }
      sendError(httpExchange, HttpURLConnection.HTTP_INTERNAL_ERROR, "Error: " + response);
    } finally {
      synchronized (this) {
        handleCount--;
      }
      httpExchange.close();
    }
  }

  private void sendError(HttpExchange httpExchange, int returnCode, String response) throws IOException {
    if (afterTheDeadlineMode) {
      String xmlResponse = "<results><message>" + escapeXml(response) + "</message></results>";
      httpExchange.sendResponseHeaders(returnCode, xmlResponse.getBytes(ENCODING).length);
      httpExchange.getResponseBody().write(xmlResponse.getBytes(ENCODING));
    } else {
      httpExchange.sendResponseHeaders(returnCode, response.getBytes(ENCODING).length);
      httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    }
  }

  private Map<String, String> getRequestQuery(HttpExchange httpExchange, URI requestedUri) throws IOException {
    final String query;
    if ("post".equalsIgnoreCase(httpExchange.getRequestMethod())) {
      query = StringTools.streamToString(httpExchange.getRequestBody(), ENCODING);
    } else {
      query = requestedUri.getRawQuery();
    }
    return parseQuery(query);
  }

  private void printListOfLanguages(HttpExchange httpExchange) throws IOException {
    setCommonHeaders(httpExchange);
    final String response = getSupportedLanguagesAsXML();
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
  }

  private void setCommonHeaders(HttpExchange httpExchange) {
    httpExchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_VALUE);
    if (allowOriginUrl != null) {
      httpExchange.getResponseHeaders().set("Access-Control-Allow-Origin", allowOriginUrl);
    }
  }

  private static Language detectLanguageOfString(final String text, final String fallbackLanguage) {
    // TODO: use identifier.isReasonablyCertain() - but make sure it works!
    if (text.length() < MIN_LENGTH_FOR_AUTO_DETECTION && fallbackLanguage != null) {
      print("Auto-detected language of text with length " + text.length() + " is not reasonably certain, using '" + fallbackLanguage + "' as fallback");
      return Language.getLanguageForShortName(fallbackLanguage);
    }
    
    final LanguageIdentifier identifier = new LanguageIdentifier(text);
    Language lang;
    try {
      lang = Language.getLanguageForShortName(identifier.getLanguage());
    } catch (IllegalArgumentException e) {
      // fall back to English
      lang = Language.getLanguageForLocale(Locale.ENGLISH);
    }
    if (lang.getDefaultLanguageVariant() != null) {
      lang = lang.getDefaultLanguageVariant();
    }
    return lang;
  }

  private void checkText(final String text, final HttpExchange httpExchange, final Map<String, String> parameters) throws Exception {
    final long timeStart = System.currentTimeMillis();
    if (text.length() > maxTextLength) {
      throw new IllegalArgumentException("Text is " + text.length() + " characters long, exceeding maximum length of " + maxTextLength);
    }
    //print("Check start: " + text.length() + " chars, " + langParam);
    final boolean autoDetectLanguage = getLanguageAutoDetect(parameters);
    final Language lang = getLanguage(text, parameters.get("language"), autoDetectLanguage);
    final String motherTongueParam = parameters.get("motherTongue");
    final Language motherTongue = motherTongueParam != null ? Language.getLanguageForShortName(motherTongueParam) : null;
    final boolean useEnabledOnly = "yes".equals(parameters.get("enabledOnly"));
    final String enabledParam = parameters.get("enabled");
    final List<String> enabledRules = new ArrayList<>();
    if (enabledParam != null) {
      enabledRules.addAll(Arrays.asList(enabledParam.split(",")));
    }
    
    final String disabledParam = parameters.get("disabled");
    final List<String> disabledRules = new ArrayList<>();
    if (disabledParam != null) {
      disabledRules.addAll(Arrays.asList(disabledParam.split(",")));
    }

    if (disabledRules.size() > 0 && useEnabledOnly) {
      throw new IllegalArgumentException("You cannot specify disabled rules using enabledOnly=yes");
    }
    
    final boolean useQuerySettings = enabledRules.size() > 0 || disabledRules.size() > 0;
    final QueryParams params = new QueryParams(enabledRules, disabledRules, useEnabledOnly, useQuerySettings);
    
    final Future<List<RuleMatch>> future = executorService.submit(new Callable<List<RuleMatch>>() {
      @Override
      public List<RuleMatch> call() throws Exception {
        return getRuleMatches(text, parameters, lang, motherTongue, params);
      }
    });
    final List<RuleMatch> matches;
    if (maxCheckTimeMillis < 0) {
      matches = future.get();
    } else {
      try {
        matches = future.get(maxCheckTimeMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        throw new RuntimeException("Text checking took longer than allowed maximum of " + maxCheckTimeMillis +
                " milliseconds (handleCount: " + handleCount + ", queue size: " + workQueue.size() +
                ", language: " + lang.getShortNameWithCountryAndVariant() +
                ", " + text.length() + " characters of text)", e);
      }
    }
    
    setCommonHeaders(httpExchange);
    String xmlResponse = getXmlResponse(text, lang, motherTongue, matches);
    String messageSent = "sent";
    String languageMessage = lang.getShortNameWithCountryAndVariant();
    final String referrer = httpExchange.getRequestHeaders().getFirst("Referer");
    try {
      httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, xmlResponse.getBytes(ENCODING).length);
      httpExchange.getResponseBody().write(xmlResponse.getBytes(ENCODING));
      if (motherTongue != null) {
        languageMessage += " (mother tongue: " + motherTongue.getShortNameWithCountryAndVariant() + ")";
      }
    } catch (IOException exception) {
      // the client is disconnected
      messageSent = "notSent: " + exception.getMessage();
    }
    print("Check done: " + text.length() + " chars, " + languageMessage + ", " + referrer + ", "
            + "handlers:" + handleCount + ", queue:" + workQueue.size() + ", " + matches.size() + " matches, "
            + (System.currentTimeMillis() - timeStart) + "ms"
            + ", " + messageSent);
  }

  private boolean getLanguageAutoDetect(Map<String, String> parameters) {
    if (afterTheDeadlineMode) {
      return "true".equals(parameters.get("guess"));
    } else {
      boolean autoDetect = "1".equals(parameters.get("autodetect"));
      if (parameters.get("language") == null && !autoDetect) {
        throw new IllegalArgumentException("Missing 'language' parameter. Specify language or use autodetect=1 for auto-detecting the language of the input text.");
      }
      return autoDetect;
    }
  }

  private Language getLanguage(String text, String langParam, boolean autoDetect) {
    final Language lang;
    if (autoDetect) {
      lang = detectLanguageOfString(text, langParam);
      print("Auto-detected language: " + lang.getShortNameWithCountryAndVariant());
    } else {
      if (afterTheDeadlineMode) {
        lang = afterTheDeadlineLanguage;
      } else {
        lang = Language.getLanguageForShortName(langParam);
      }
    }
    return lang;
  }

  private List<RuleMatch> getRuleMatches(String text, Map<String, String> parameters, Language lang,
                                         Language motherTongue, QueryParams params) throws Exception {
    final String sourceText = parameters.get("srctext");
    if (sourceText == null) {
      final JLanguageTool lt = getLanguageToolInstance(lang, motherTongue, params);
      return lt.check(text);
    } else {
      if (parameters.get("motherTongue") == null) {
        throw new IllegalArgumentException("Missing 'motherTongue' parameter for bilingual checks");
      }
      print("Checking bilingual text, with source length " + sourceText.length() +
          " and target length " + text.length() + " (characters), source language " +
          motherTongue + " and target language " + lang.getShortNameWithCountryAndVariant());
      final JLanguageTool sourceLt = getLanguageToolInstance(motherTongue, null, params);
      final JLanguageTool targetLt = getLanguageToolInstance(lang, null, params);
      final List<BitextRule> bRules = Tools.getBitextRules(motherTongue, lang);
      return Tools.checkBitext(sourceText, text, sourceLt, targetLt, bRules);
    }
  }

  private String getXmlResponse(String text, Language lang, Language motherTongue, List<RuleMatch> matches) {
    if (afterTheDeadlineMode) {
      AtDXmlSerializer serializer = new AtDXmlSerializer();
      return serializer.ruleMatchesToXml(matches, text);
    } else {
      RuleAsXmlSerializer serializer = new RuleAsXmlSerializer();
      return serializer.ruleMatchesToXml(matches, text, CONTEXT_SIZE, lang, motherTongue);
    }
  }

  private Map<String, String> parseQuery(String query) throws UnsupportedEncodingException {
    final Map<String, String> parameters = new HashMap<>();
    if (query != null) {
      final String[] pairs = query.split("[&]");
      final Map<String, String> parameterMap = getParameterMap(pairs);
      parameters.putAll(parameterMap);
    }
    return parameters;
  }

  private Map<String, String> getParameterMap(String[] pairs) throws UnsupportedEncodingException {
    final Map<String, String> parameters = new HashMap<>();
    for (String pair : pairs) {
      final int delimPos = pair.indexOf('=');
      if (delimPos != -1) {
        final String param = pair.substring(0, delimPos);
        final String key = URLDecoder.decode(param, ENCODING);
        final String value = URLDecoder.decode(pair.substring(delimPos + 1), ENCODING);
        parameters.put(key, value);
      }
    }
    return parameters;
  }

  private static void print(String s) {
    print(s, System.out);
  }

  private static void print(String s, PrintStream outputStream) {
    final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    final String now = dateFormat.format(new Date());
    outputStream.println(now + " " + s);
  }

  /**
   * Create a JLanguageTool instance for a specific language, mother tongue, and rule configuration.
   *
   * @param lang the language to be used.
   * @param motherTongue the user's mother tongue or {@code null}
   */
  private JLanguageTool getLanguageToolInstance(Language lang, Language motherTongue, QueryParams params) throws Exception {
    final JLanguageTool newLanguageTool = new JLanguageTool(lang, motherTongue);
    newLanguageTool.activateDefaultPatternRules();
    newLanguageTool.activateDefaultFalseFriendRules();
    final Configuration config = new Configuration(lang);
    if (!params.useQuerySettings && internalServer && config.getUseGUIConfig()) { // use the GUI config values
      configureGUI(newLanguageTool, config);
    }
    if (params.useQuerySettings) {
      Tools.selectRules(newLanguageTool, params.disabledRules, params.enabledRules, params.useEnabledOnly);
    }
    return newLanguageTool;
  }

  private void configureGUI(JLanguageTool langTool, Configuration config) {
    print("Using options configured in the GUI");
    //TODO: add a parameter to config to set language
    final Set<String> disabledRules = config.getDisabledRuleIds();
    if (disabledRules != null) {
      for (final String ruleId : disabledRules) {
        langTool.disableRule(ruleId);
      }
    }
    final Set<String> disabledCategories = config.getDisabledCategoryNames();
    if (disabledCategories != null) {
      for (final String categoryName : disabledCategories) {
        langTool.disableCategory(categoryName);
      }
    }
    final Set<String> enabledRules = config.getEnabledRuleIds();
    if (enabledRules != null) {
      for (String ruleName : enabledRules) {
        langTool.enableDefaultOffRule(ruleName);
        langTool.enableRule(ruleName);
      }
    }
  }

  /**
   * Construct an XML string containing all supported languages. <br/>The XML format looks like this:<br/><br/>
   * &lt;languages&gt;<br/>
   *    &nbsp;&nbsp;&lt;language name="Catalan" abbr="ca" abbrWithVariant="ca-ES"/&gt;<br/>
   *    &nbsp;&nbsp;&lt;language name="German" abbr="de" abbrWithVariant="de"/&gt;<br/>
   *    &nbsp;&nbsp;&lt;language name="German (Germany)" abbr="de" abbrWithVariant="de-DE"/&gt;<br/>
   *  &lt;/languages&gt;<br/><br/>
   *  The languages are sorted alphabetically by their name.
   * @return an XML document listing all supported languages
   */
  public static String getSupportedLanguagesAsXML() {
    final Language[] languageCopy = Language.REAL_LANGUAGES.clone();
    final List<Language> languages = Arrays.asList(languageCopy);
    Collections.sort(languages, new Comparator<Language>() {
      @Override
      public int compare(Language o1, Language o2) {
        return o1.getName().compareTo(o2.getName());
      }
    });
    final StringBuilder xmlBuffer = new StringBuilder("<?xml version='1.0' encoding='" + ENCODING + "'?>\n<languages>\n");
    for (Language lang : languages) {
      xmlBuffer.append(String.format("\t<language name=\"%s\" abbr=\"%s\" abbrWithVariant=\"%s\"/> \n", lang.getName(),
              lang.getShortName(), lang.getShortNameWithCountryAndVariant()));
    }
    xmlBuffer.append("</languages>\n");
    return xmlBuffer.toString();
  }

  private class QueryParams {
    final List<String> enabledRules;
    final List<String> disabledRules;
    final boolean useEnabledOnly;
    final boolean useQuerySettings;

    QueryParams(List<String> enabledRules, List<String> disabledRules, boolean useEnabledOnly, boolean useQuerySettings) {
      this.enabledRules = enabledRules;
      this.disabledRules = disabledRules;
      this.useEnabledOnly = useEnabledOnly;
      this.useQuerySettings = useQuerySettings;
    }
  }

}
