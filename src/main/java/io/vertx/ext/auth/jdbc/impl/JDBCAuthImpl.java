/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.auth.jdbc.impl;


import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VertxException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.impl.LoggerFactory;
import io.vertx.ext.auth.AbstractUser;
import io.vertx.ext.auth.AuthProvider;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jdbc.JDBCAuth;
import io.vertx.ext.auth.jdbc.JDBCHashStrategy;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;


/**
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class JDBCAuthImpl implements AuthProvider, JDBCAuth {

  private static final Logger log = LoggerFactory.getLogger(JDBCAuthImpl.class);

  private JDBCClient client;
  private String authenticateQuery = DEFAULT_AUTHENTICATE_QUERY;
  private String rolesQuery = DEFAULT_ROLES_QUERY;
  private String permissionsQuery = DEFAULT_PERMISSIONS_QUERY;
  private JDBCHashStrategy strategy = new DefaultHashStrategy();

  public JDBCAuthImpl(JDBCClient client) {
    this.client = client;
  }

  @Override
  public void authenticate(JsonObject authInfo, Handler<AsyncResult<User>> resultHandler) {

    String username = authInfo.getString("username");
    if (username == null) {
      resultHandler.handle(Future.failedFuture("authInfo must contain username in 'username' field"));
      return;
    }
    String password = authInfo.getString("password");
    if (password == null) {
      resultHandler.handle(Future.failedFuture("authInfo must contain password in 'password' field"));
      return;
    }
    executeQuery(authenticateQuery, new JsonArray().add(username), resultHandler, rs -> {

      switch (rs.getNumRows()) {
        case 0: {
          // Unknown user/password
          resultHandler.handle(Future.failedFuture("Invalid username/password"));
          break;
        }
        case 1: {
          JsonArray row = rs.getResults().get(0);
          String hashedStoredPwd = strategy.getHashedStoredPwd(row);
          String salt = strategy.getSalt(row);
          String hashedPassword = strategy.computeHash(password, salt);
          if (hashedStoredPwd.equals(hashedPassword)) {
            resultHandler.handle(Future.succeededFuture(new JDBCUser(username)));
          } else {
            resultHandler.handle(Future.failedFuture("Invalid username/password"));
          }
          break;
        }
        default: {
          // More than one row returned!
          resultHandler.handle(Future.failedFuture("Failure in authentication"));
          break;
        }
      }
    });
  }

  @Override
  public User fromBuffer(Buffer buffer) {
    JDBCUser user = new JDBCUser();
    user.readFromBuffer(0, buffer);
    return user;
  }

  @Override
  public JDBCAuth setAuthenticationQuery(String authenticationQuery) {
    this.authenticateQuery = authenticationQuery;
    return this;
  }

  @Override
  public JDBCAuth setRolesQuery(String rolesQuery) {
    this.rolesQuery = rolesQuery;
    return this;
  }

  @Override
  public JDBCAuth setPermissionsQuery(String permissionsQuery) {
    this.permissionsQuery = permissionsQuery;
    return this;
  }

  @Override
  public JDBCAuth setHashStrategy(JDBCHashStrategy strategy) {
    this.strategy = strategy;
    return this;
  }

  private class JDBCUser extends AbstractUser {

    String username;
    JsonObject principal;

    JDBCUser() {
    }

    JDBCUser(String username) {
      this.username = username;
    }

    @Override
    public void doHasRole(String role, Handler<AsyncResult<Boolean>> resultHandler) {
      hasRoleOrPermission(role, rolesQuery, resultHandler);
    }

    @Override
    public void doHasPermission(String permission, Handler<AsyncResult<Boolean>> resultHandler) {
      hasRoleOrPermission(permission, permissionsQuery, resultHandler);
    }

    @Override
    public void doHasRoles(Set<String> roles, Handler<AsyncResult<Boolean>> resultHandler) {
      hasAllRolesOrPermissions(roles, rolesQuery, resultHandler);
    }

    @Override
    public void doHasPermissions(Set<String> permissions, Handler<AsyncResult<Boolean>> resultHandler) {
      hasAllRolesOrPermissions(permissions, permissionsQuery, resultHandler);
    }

    @Override
    public JsonObject principal() {
      if (principal == null) {
        principal = new JsonObject().put("username", username);
      }
      return principal;
    }

    @Override
    public boolean isClusterable() {
      return true;
    }

    @Override
    public void writeToBuffer(Buffer buff) {
      super.writeToBuffer(buff);
      byte[] bytes = username.getBytes(StandardCharsets.UTF_8);
      buff.appendInt(bytes.length);
      buff.appendBytes(bytes);
    }

    @Override
    public int readFromBuffer(int pos, Buffer buffer) {
      pos = super.readFromBuffer(pos, buffer);
      int len = buffer.getInt(pos);
      pos += 4;
      byte[] bytes = buffer.getBytes(pos, pos + len);
      username = new String(bytes, StandardCharsets.UTF_8);
      pos += len;
      return pos;
    }

    private void hasRoleOrPermission(String roleOrPermission, String query, Handler<AsyncResult<Boolean>> resultHandler) {
      executeQuery(query, new JsonArray().add(username), resultHandler, rs -> {
        boolean has = false;
        for (JsonArray result: rs.getResults()) {
          String theRoleOrPermission = result.getString(0);
          if (roleOrPermission.equals(theRoleOrPermission)) {
            resultHandler.handle(Future.succeededFuture(true));
            has = true;
            break;
          }
        }
        if (!has) {
          resultHandler.handle(Future.succeededFuture(false));
        }
      });
    }

    private void hasAllRolesOrPermissions(Set<String> rolesOrPermissions, String query, Handler<AsyncResult<Boolean>> resultHandler) {
      Set<String> copy = new HashSet<>(rolesOrPermissions);
      executeQuery(query, new JsonArray().add(username), resultHandler, rs -> {
        boolean hasAll = false;
        for (JsonArray result: rs.getResults()) {
          String theRoleOrPermission = result.getString(0);
          copy.remove(theRoleOrPermission);
          if (copy.isEmpty()) {
            hasAll = true;
            resultHandler.handle(Future.succeededFuture(true));
            break;
          }
        }
        if (!hasAll) {
          resultHandler.handle(Future.succeededFuture(false));
        }
      });
    }
  }

  protected <T> void executeQuery(String query, JsonArray params, Handler<AsyncResult<T>> resultHandler,
                                Consumer<ResultSet> resultSetConsumer) {
    client.getConnection(res -> {
      if (res.succeeded()) {
        SQLConnection conn = res.result();
        conn.queryWithParams(query, params, queryRes -> {
          if (queryRes.succeeded()) {
            ResultSet rs = queryRes.result();
            resultSetConsumer.accept(rs);
          } else {
            resultHandler.handle(Future.failedFuture(queryRes.cause()));
          }
          conn.close(closeRes -> {});
        });
      } else {
        resultHandler.handle(Future.failedFuture(res.cause()));
      }
    });
  }

  private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

  public static String bytesToHex(byte[] bytes) {
    char[] chars = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int x = 0xFF & bytes[i];
      chars[i * 2] = HEX_CHARS[x >>> 4];
      chars[1 + i * 2] = HEX_CHARS[0x0F & x];
    }
    return new String(chars);
  }

  public static String computeHash(String password, String salt, String algo) {
    try {
      MessageDigest md = MessageDigest.getInstance(algo);
      String concat = (salt == null ? "" : salt) + password;
      byte[] bHash = md.digest(concat.getBytes(StandardCharsets.UTF_8));
      return bytesToHex(bHash);
    } catch (NoSuchAlgorithmException e) {
      throw new VertxException(e);
    }
  }

  private class DefaultHashStrategy implements JDBCHashStrategy {

    @Override
    public String computeHash(String password, String salt) {
      return JDBCAuthImpl.computeHash(password, salt, "SHA-512");
    }

    @Override
    public String getHashedStoredPwd(JsonArray row) {
      return row.getString(0);
    }

    @Override
    public String getSalt(JsonArray row) {
      return row.getString(1);
    }
  }

}
