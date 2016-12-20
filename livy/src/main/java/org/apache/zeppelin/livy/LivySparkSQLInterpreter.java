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

import org.apache.zeppelin.interpreter.*;
import org.apache.zeppelin.interpreter.thrift.InterpreterCompletion;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Livy PySpark interpreter for Zeppelin.
 */
public class LivySparkSQLInterpreter extends Interpreter {

  Logger LOGGER = LoggerFactory.getLogger(LivySparkSQLInterpreter.class);

  protected Map<String, Integer> userSessionMap;
  private LivyHelper livyHelper;
  private AtomicBoolean sparkVersionDetected = new AtomicBoolean(false);
  private AtomicBoolean isSpark2 = new AtomicBoolean(false);
  private int maxResult;

  public LivySparkSQLInterpreter(Properties property) {
    super(property);
    livyHelper = new LivyHelper(property);
    userSessionMap = LivySparkInterpreter.getUserSessionMap();
    maxResult = Integer.parseInt(property.getProperty("zeppelin.livy.spark.sql.maxResult"));
  }

  @Override
  public void open() {
  }

  @Override
  public void close() {
    livyHelper.closeSession(userSessionMap);
  }

  @Override
  public InterpreterResult interpret(String line, InterpreterContext interpreterContext) {
    try {
      if (userSessionMap.get(interpreterContext.getAuthenticationInfo().getUser()) == null) {
        try {
          userSessionMap.put(
              interpreterContext.getAuthenticationInfo().getUser(),
              livyHelper.createSession(
                  interpreterContext,
                  "spark")
          );
        } catch (Exception e) {
          LOGGER.error("Exception in LivySparkSQLInterpreter while interpret ", e);
          return new InterpreterResult(InterpreterResult.Code.ERROR, e.getMessage());
        }
      }

      if (line == null || line.trim().length() == 0) {
        return new InterpreterResult(InterpreterResult.Code.SUCCESS, "");
      }

      if (!sparkVersionDetected.getAndSet(true)) {
        // As we don't know whether livyserver use spark2 or spark1, so we will detect SparkSession
        // to judge whether it is using spark2.
        try {
          InterpreterResult result = livyHelper.interpret("spark", interpreterContext,
              userSessionMap);
          if (result.code() == InterpreterResult.Code.SUCCESS &&
              result.message().contains("org.apache.spark.sql.SparkSession")) {
            LOGGER.info("SparkSession is detected so we are using spark 2.x for session {}",
                userSessionMap.get(interpreterContext.getAuthenticationInfo().getUser()));
            isSpark2.set(true);
          } else {
            // spark 1.x
            result = livyHelper.interpret("sqlContext", interpreterContext,
                userSessionMap);
            if (result.code() == InterpreterResult.Code.SUCCESS) {
              LOGGER.info("sqlContext is detected.");
            } else if (result.code() == InterpreterResult.Code.ERROR) {
              // create SqlContext if it is not available, as in livy 0.2 sqlContext
              // is not available.
              LOGGER.info("sqlContext is not detected, try to create SQLContext by ourselves");
              result = livyHelper.interpret(
                  "val sqlContext = new org.apache.spark.sql.SQLContext(sc)\n"
                      + "import sqlContext.implicits._", interpreterContext, userSessionMap);
              if (result.code() == InterpreterResult.Code.ERROR) {
                throw new Exception("Fail to create SQLContext," + result.message());
              }
            }
          }
        } catch (Exception e) {
          throw new RuntimeException("Fail to Detect SparkVersion", e);
        }
      }

      // use triple quote so that we don't need to do string escape.
      String sqlQuery = null;
      if (isSpark2.get()) {
        sqlQuery = "spark.sql(\"\"\"" + line + "\"\"\").show(" + maxResult + ")";
      } else {
        sqlQuery = "sqlContext.sql(\"\"\"" + line + "\"\"\").show(" + maxResult + ")";
      }

      InterpreterResult res = livyHelper.interpret(sqlQuery, interpreterContext, userSessionMap);

      if (res.code() == InterpreterResult.Code.SUCCESS) {
        StringBuilder resMsg = new StringBuilder();
        resMsg.append("%table ");
        String[] rows = res.message().split("\n");

        String[] headers = rows[1].split("\\|");
        for (int head = 1; head < headers.length; head++) {
          resMsg.append(headers[head].trim()).append("\t");
        }
        resMsg.append("\n");
        if (rows[3].indexOf("+") == 0) {

        } else {
          for (int cols = 3; cols < rows.length - 1; cols++) {
            String[] col = rows[cols].split("\\|");
            for (int data = 1; data < col.length; data++) {
              resMsg.append(col[data].trim()).append("\t");
            }
            resMsg.append("\n");
          }
        }
        if (rows[rows.length - 1].indexOf("only") == 0) {
          resMsg.append("<font color=red>" + rows[rows.length - 1] + ".</font>");
        }

        return new InterpreterResult(InterpreterResult.Code.SUCCESS,
            resMsg.toString()
        );
      } else {
        return res;
      }


    } catch (Exception e) {
      LOGGER.error("Exception in LivySparkSQLInterpreter while interpret ", e);
      return new InterpreterResult(InterpreterResult.Code.ERROR,
          InterpreterUtils.getMostRelevantMessage(e));
    }
  }

  public boolean concurrentSQL() {
    return Boolean.parseBoolean(getProperty("zeppelin.livy.concurrentSQL"));
  }

  @Override
  public void cancel(InterpreterContext context) {
    livyHelper.cancelHTTP(context.getParagraphId());
  }

  @Override
  public FormType getFormType() {
    return FormType.SIMPLE;
  }

  @Override
  public int getProgress(InterpreterContext context) {
    return 0;
  }

  @Override
  public Scheduler getScheduler() {
    if (concurrentSQL()) {
      int maxConcurrency = 10;
      return SchedulerFactory.singleton().createOrGetParallelScheduler(
          LivySparkInterpreter.class.getName() + this.hashCode(), maxConcurrency);
    } else {
      Interpreter intp =
          getInterpreterInTheSameSessionByClassName(LivySparkInterpreter.class.getName());
      if (intp != null) {
        return intp.getScheduler();
      } else {
        return null;
      }
    }
  }

  @Override
  public List<InterpreterCompletion> completion(String buf, int cursor) {
    return null;
  }

}
