/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.livy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterResult.Code;
import org.apache.zeppelin.interpreter.InterpreterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Properties;


/***
 * Livy helper class
 */
public class LivyHelper {
  Logger LOGGER = LoggerFactory.getLogger(LivyHelper.class);
  Gson gson = new GsonBuilder().setPrettyPrinting().create();
  Properties property;

  LivyHelper(Properties property) {
    this.property = property;
  }

  protected Integer createSession(String user, String kind) throws Exception {
    try {
      String json = executeHTTP(property.getProperty("zeppelin.livy.url") + "/sessions",
          "POST",
          "{\"kind\": \"" + kind + "\", \"proxyUser\": \"" + user + "\"}"
      );
      Map jsonMap = (Map<Object, Object>) gson.fromJson(json,
          new TypeToken<Map<Object, Object>>() {
          }.getType());
      Integer sessionId = ((Double) jsonMap.get("id")).intValue();
      if (!jsonMap.get("state").equals("idle")) {
        while (true) {
          LOGGER.error(String.format("sessionId:%s state is %s",
              jsonMap.get("id"), jsonMap.get("state")));
          Thread.sleep(1000);
          json = executeHTTP(property.getProperty("zeppelin.livy.url") + "/sessions/" + sessionId,
              "GET", null);
          jsonMap = (Map<Object, Object>) gson.fromJson(json,
              new TypeToken<Map<Object, Object>>() {
              }.getType());
          if (jsonMap.get("state").equals("idle")) {
            break;
          }
        }
      }
      return sessionId;
    } catch (Exception e) {
      LOGGER.error("Error getting session for user", e);
      throw e;
    }
  }

  protected void initializeSpark(final InterpreterContext context,
                                 final Map<String, Integer> userSessionMap) throws Exception {
    interpret("val sqlContext= new org.apache.spark.sql.SQLContext(sc)\n" +
        "import sqlContext.implicits._", context, userSessionMap);
  }

  public InterpreterResult interpretInput(String stringLines,
                                          final InterpreterContext context,
                                          final Map<String, Integer> userSessionMap,
                                          LivyOutputStream out) {
    try {
      String[] lines = stringLines.split("\n");
      String[] linesToRun = new String[lines.length + 1];
      for (int i = 0; i < lines.length; i++) {
        linesToRun[i] = lines[i];
      }
      linesToRun[lines.length] = "print(\"\")";

      out.setInterpreterOutput(context.out);
      context.out.clear();
      Code r = null;
      String incomplete = "";

      for (int l = 0; l < linesToRun.length; l++) {
        String s = linesToRun[l];
        // check if next line starts with "." (but not ".." or "./") it is treated as an invocation
        //for spark
        if (l + 1 < linesToRun.length) {
          String nextLine = linesToRun[l + 1].trim();
          if (nextLine.startsWith(".")
              && !nextLine.startsWith("..")
              && !nextLine.startsWith("./")) {
            incomplete += s + "\n";
            continue;
          }
        }

        InterpreterResult res;
        try {
          res = interpret(incomplete + s, context, userSessionMap);
        } catch (Exception e) {
          LOGGER.info("Interpreter exception", e);
          return new InterpreterResult(Code.ERROR, InterpreterUtils.getMostRelevantMessage(e));
        }

        r = getResultCode(res);

        if (r == Code.ERROR) {
          out.setInterpreterOutput(null);
          return new InterpreterResult(r, "");
        } else if (r == Code.INCOMPLETE) {
          incomplete += s + "\n";
        } else {
          out.write((res.message() + "\n").getBytes(Charset.forName("UTF-8")));
          incomplete = "";
        }
      }

      if (r == Code.INCOMPLETE) {
        out.setInterpreterOutput(null);
        return new InterpreterResult(r, "Incomplete expression");
      } else {
        out.setInterpreterOutput(null);
        return new InterpreterResult(Code.SUCCESS);
      }

    } catch (Exception e) {
      LOGGER.error("error in interpretInput", e);
      return new InterpreterResult(Code.ERROR, e.getMessage());
    }
  }

  private Code getResultCode(InterpreterResult r) {
    if (r.code().equals(Code.SUCCESS)) {
      return Code.SUCCESS;
    } else if (r.code().equals(Code.INCOMPLETE)) {
      return Code.INCOMPLETE;
    } else {
      return Code.ERROR;
    }
  }

  protected InterpreterResult interpret(String stringLines,
                                        final InterpreterContext context,
                                        final Map<String, Integer> userSessionMap)
      throws Exception {
    stringLines = stringLines
        //for "\n" present in string
        .replaceAll("\\\\n", "\\\\\\\\n")
        //for new line present in string
        .replaceAll("\\n", "\\\\n")
        // for \" present in string
        .replaceAll("\\\\\"", "\\\\\\\\\"")
        // for " present in string
        .replaceAll("\"", "\\\\\"");

    if (stringLines.trim().equals("")) {
      return new InterpreterResult(Code.SUCCESS, "");
    }
    Map jsonMap = executeCommand(stringLines, context, userSessionMap);
    Integer id = ((Double) jsonMap.get("id")).intValue();
    InterpreterResult res = getResultFromMap(jsonMap);
    if (res != null) {
      return res;
    }

    while (true) {
      Thread.sleep(1000);
      jsonMap = getStatusById(context, userSessionMap, id);
      LOGGER.error("jsonMap==" + jsonMap);
      InterpreterResult interpreterResult = getResultFromMap(jsonMap);
      if (interpreterResult != null) {
        return interpreterResult;
      }
    }
  }

  private InterpreterResult getResultFromMap(Map jsonMap) {
    if (jsonMap.get("state").equals("available")) {
      if (((Map) jsonMap.get("output")).get("status").equals("error")) {
        StringBuilder errorMessage = new StringBuilder((String) ((Map) jsonMap
            .get("output")).get("evalue"));
        if (errorMessage.toString().equals("incomplete statement")
            || errorMessage.toString().contains("EOF")) {
          return new InterpreterResult(Code.INCOMPLETE, "");
        }
        String traceback = gson.toJson(((Map) jsonMap.get("output")).get("traceback"));
        if (!traceback.equals("[]")) {
          errorMessage
              .append("\n")
              .append("traceback: \n")
              .append(traceback);
        }

        return new InterpreterResult(Code.ERROR, errorMessage.toString());
      }
      if (((Map) jsonMap.get("output")).get("status").equals("ok")) {
        String result = (String) ((Map) ((Map) jsonMap.get("output"))
            .get("data")).get("text/plain");
        if (result != null) {
          result = result.trim();
          if (result.startsWith("<link")
              || result.startsWith("<script")
              || result.startsWith("<style")
              || result.startsWith("<div")) {
            result = "%html " + result;
          }
        }
        return new InterpreterResult(Code.SUCCESS, result);
      }
    }
    return null;
  }

  private Map executeCommand(String lines, InterpreterContext context,
                             Map<String, Integer> userSessionMap) throws Exception {
    String json = executeHTTP(property.get("zeppelin.livy.url") + "/sessions/"
            + userSessionMap.get(context.getAuthenticationInfo().getUser())
            + "/statements",
        "POST",
        "{\"code\": \"" + lines + "\" }");
    if (json.equals("Session not found")) {
      throw new Exception("Exception: Session not found, Livy server would have restarted, " +
          "or lost session.");
    }
    try {

      Map jsonMap = gson.fromJson(json,
          new TypeToken<Map>() {
          }.getType());
      return jsonMap;
    } catch (Exception e) {
      throw e;
    }
  }

  private Map getStatusById(InterpreterContext context,
                            Map<String, Integer> userSessionMap, Integer id) throws Exception {
    String json = executeHTTP(property.getProperty("zeppelin.livy.url") + "/sessions/"
            + userSessionMap.get(context.getAuthenticationInfo().getUser())
            + "/statements/" + id,
        "GET", null);
    try {
      Map jsonMap = gson.fromJson(json,
          new TypeToken<Map>() {
          }.getType());
      return jsonMap;
    } catch (Exception e) {
      throw e;
    }
  }

  public String executeHTTP(String targetURL, String method, String jsonData)
      throws Exception {
    HttpClient client = HttpClientBuilder.create().build();
    HttpResponse response;
    if (method.equals("POST")) {
      HttpPost request = new HttpPost(targetURL);
      request.addHeader("Content-Type", "application/json");
      StringEntity se = new StringEntity(jsonData);
      request.setEntity(se);
      response = client.execute(request);
    } else {
      HttpGet request = new HttpGet(targetURL);
      request.addHeader("Content-Type", "application/json");
      response = client.execute(request);
    }

    if (response.getStatusLine().getStatusCode() == 200
        || response.getStatusLine().getStatusCode() == 201
        || response.getStatusLine().getStatusCode() == 404) {
      BufferedReader rd = new BufferedReader(
          new InputStreamReader(response.getEntity().getContent()));

      StringBuffer result = new StringBuffer();
      String line = "";
      while ((line = rd.readLine()) != null) {
        result.append(line);
      }
      return result.toString();
    }
    return null;
  }

}
