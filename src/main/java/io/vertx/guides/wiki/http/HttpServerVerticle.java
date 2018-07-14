/*
 *  Copyright (c) 2017 Red Hat, Inc. and/or its affiliates.
 *  Copyright (c) 2017 INSA Lyon, CITI Laboratory.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vertx.guides.wiki.http;

import io.vertx.core.eventbus.EventBus;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;


import com.rethinkdb.net.Cursor;

import java.util.HashMap;
import java.util.List;

import io.vertx.ext.web.handler.sockjs.SockJSHandler;

import com.rethinkdb.RethinkDB;
import com.rethinkdb.gen.exc.ReqlError;
import com.rethinkdb.gen.exc.ReqlQueryLogicError;
import com.rethinkdb.model.MapObject;
import com.rethinkdb.net.Connection;


import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.handler.StaticHandler;
import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.KeyStoreOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.ext.jwt.JWTOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.auth.AuthProvider;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.shiro.ShiroAuth;
import io.vertx.ext.auth.shiro.ShiroAuthOptions;
import io.vertx.ext.auth.shiro.ShiroAuthRealmType;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.handler.AuthHandler;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.FormLoginHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.RedirectAuthHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.handler.UserSessionHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;
import io.vertx.guides.wiki.database.WikiDatabaseService;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author <a href="https://julien.ponge.org/">Julien Ponge</a>
 */
public class HttpServerVerticle extends AbstractVerticle {



  public static final RethinkDB r = RethinkDB.r;
  public static final String DBHOST = "rethinkdb-stable";
  public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
  public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);

  private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();

  private WikiDatabaseService dbService;

  private WebClient webClient;

  private static final String EMPTY_PAGE_MARKDOWN =
    "# A new page\n" +
      "\n" +
      "Feel-free to write in Markdown!\n";


 // public static final RethinkDB r = RethinkDB.r;

  @Override
  public void start(Future<Void> startFuture) throws Exception {

    String wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue");
    dbService = WikiDatabaseService.createProxy(vertx, wikiDbQueue);

    webClient = WebClient.create(vertx, new WebClientOptions()
      .setSsl(true)
      .setUserAgent("vert-x3"));

    // tag::https-server[]
    HttpServer server = vertx.createHttpServer(new HttpServerOptions()
      .setSsl(true)
      .setKeyStoreOptions(new JksOptions()
        .setPath("server-keystore.jks")
        .setPassword("secret")));
    // end::https-server[]

    // tag::shiro-auth[]
    AuthProvider auth = ShiroAuth.create(vertx, new ShiroAuthOptions()
      .setType(ShiroAuthRealmType.PROPERTIES)
      .setConfig(new JsonObject()
        .put("properties_path", "classpath:wiki-users.properties")));
    // end::shiro-auth[]

    // tag::shiro-routes[]
    Router router = Router.router(vertx);

    router.route().handler(CookieHandler.create());
    router.route().handler(BodyHandler.create());
    router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));
    router.route().handler(UserSessionHandler.create(auth));  // <1>

    AuthHandler authHandler = RedirectAuthHandler.create(auth, "/"); // <2>
    
    //router.route("/").handler(authHandler);  // <3>
    router.route("/wiki/*").handler(authHandler);
    router.route("/action/*").handler(authHandler);

    //router.get("/").handler(this::indexHandler);
    router.get("/wiki/:page").handler(this::pageRenderingHandler);
    router.post("/action/save").handler(this::pageUpdateHandler);
    router.post("/action/create").handler(this::pageCreateHandler);
    router.get("/action/backup").handler(this::backupHandler);
    router.post("/action/delete").handler(this::pageDeletionHandler);
    router.get("/tv_shows/").handler(this::tvShowsHandler);
    // end::shiro-routes[]

    // tag::shiro-login[]
    //router.get("/login").handler(this::loginHandler);


    router.get("/app/*").handler(StaticHandler.create().setCachingEnabled(false)); // <1> <2>
    router.get("/").handler(context -> {

      //LOGGER.info("HTTP server");

      //context.currentRoute().remove();
      context.reroute("/app/index.html");
    });

     router.post("/login-auth").handler(ctx -> {
      JsonObject credentials = ctx.getBodyAsJson();

      LOGGER.info("holla"+credentials);
      if (credentials == null) {
        // bad request
        ctx.fail(400);
        return;
      }

      // use the auth handler to perform the authentication for us
      auth.authenticate(credentials, login -> {
        // error handling
        if (login.failed()) {
          // forbidden

              ctx.response().setStatusCode(401);
              ctx.response().putHeader("Content-Type", "application/json");
              ctx.response().end(new JsonObject()
        .put("success", false)
        .put("message", "Username or password is incorrect").encode());


              //end(new JsonObject().put("success", false).encode());
            //  ctx.response().end(new JsonObject().put("message" : "Username or password is incorrect").encode());
              
          //ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json").end("{ success: false, message: 'Username or password is incorrect' }");
          ctx.fail(401);
          //ctx.reroute("/app/index.html");
          return;
        }

        ctx.setUser(login.result());
        //ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json").end("{ success: true }");

              ctx.response().setStatusCode(200);
              ctx.response().putHeader("Content-Type", "application/json");
              ctx.response().end(new JsonObject().put("success", true).encode());
      });
});

  //  router.post("/login-auth").handler(FormLoginHandler.create(auth));

   //   c -> {
     //           LOGGER.info("HTTP server"+ c.currentRoute());
//
  //          }
        //

   // <1>

    router.get("/logout").handler(context -> {
      context.clearUser();  // <2>
      context.response()
        .setStatusCode(302)
        .putHeader("Location", "/")
        .end();
    });
    // end::shiro-login[]

    // tag::jwtAuth[]
    Router apiRouter = Router.router(vertx);

    JWTAuth jwtAuth = JWTAuth.create(vertx, new JWTAuthOptions()
      .setKeyStore(new KeyStoreOptions()
        .setPath("keystore.jceks")
        .setType("jceks")
        .setPassword("secret")));

    apiRouter.route().handler(JWTAuthHandler.create(jwtAuth, "/api/token"));
    // end::jwtAuth[]

    // tag::issue-jwt[]
    apiRouter.get("/token").handler(context -> {

      JsonObject creds = new JsonObject()
        .put("username", context.request().getHeader("login"))
        .put("password", context.request().getHeader("password"));
      auth.authenticate(creds, authResult -> {  // <1>

        if (authResult.succeeded()) {
          User user = authResult.result();
          user.isAuthorized("create", canCreate -> {  // <2>
            user.isAuthorized("delete", canDelete -> {
              user.isAuthorized("update", canUpdate -> {

                String token = jwtAuth.generateToken( // <3>
                  new JsonObject()
                    .put("username", context.request().getHeader("login"))
                    .put("canCreate", canCreate.succeeded() && canCreate.result())
                    .put("canDelete", canDelete.succeeded() && canDelete.result())
                    .put("canUpdate", canUpdate.succeeded() && canUpdate.result()),
                  new JWTOptions()
                    .setSubject("Wiki API")
                    .setIssuer("Vert.x"));
                context.response().putHeader("Content-Type", "text/plain").end(token);
              });
            });
          });
        } else {
          context.fail(401);
        }
      });
    });
    // end::issue-jwt[]

    apiRouter.get("/pages").handler(this::apiRoot);
    apiRouter.get("/pages/:id").handler(this::apiGetPage);
    apiRouter.post().handler(BodyHandler.create());
    apiRouter.post("/pages").handler(this::apiCreatePage);
    apiRouter.put().handler(BodyHandler.create());
    apiRouter.put("/pages/:id").handler(this::apiUpdatePage);
    apiRouter.delete("/pages/:id").handler(this::apiDeletePage);
    router.mountSubRouter("/api", apiRouter);

    

    EventBus bus = vertx.eventBus();

    new Thread(() -> {
      Connection conn = null;

      try {
        conn = r.connection().hostname("localhost").port(28015).connect();
        //conn = r.connection().hostname(DBHOST).connect();
         Cursor<HashMap> cur = //r.db("vertx_rethink").table("tv_shows").changes().getField("new_val").without("time").run(conn);
                            r.db("vertx_rethink")
                            .table("tv_shows").changes()
                            //.optArg("include_initial", true)
                            .filter( row -> row.g("old_val").eq(null))
                            //.getField("new_val")
                           
                            .run(conn);//.filter(row -> row.getField("visited").eq(false)).run(conn);
                            //.filter(row -> row.getField("visited").eq(false))

        JsonArray events = new JsonArray();
        
        Long result;
        for (HashMap item : cur)
              {
                  result = r.db("vertx_rethink").table("tv_shows").count().run(conn);



                  //if(item.get("visited").equals("false"))
                //i = r.db("vertx_rethink").table("tv_shows").count().run(conn);
                      //{
                          //System.out.println(item);
                          //r.db("vertx_rethink").table("tv_shows").get(item.get("id"))
                            //  .update(r.hashMap("visited", "true")).run(conn);
                              LOGGER.info("Row Inserted ");
                  
                  JsonObject event = new JsonObject()
                      .put("result", result.toString());
                      //.put("id", item.get("id"))
                      //.put("name", item.get("name")) // <2>
                      //.put("tv_show", item.get("tv_show"))
                      //.put("visited", item.get("visited"));

                      events.add(event);

                  //r.db("vertx_rethink").table("tv_shows").get(item.get("id")).update(r.hashMap("visited", true)).run(conn);
                  bus.send("page.saved", event);
                     // }
                  
                  
                }

                
      }
      catch (Exception e) {
        System.err.println("Error: changefeed failed");
        LOGGER.error("Error: changefeed failed");
      }
      finally {
       LOGGER.error("Connection Closed"); 
        conn.close();
      }
    }).start();


     router.route("/eventbus/*").handler(
      SockJSHandler.create(vertx).bridge(
        new BridgeOptions().addOutboundPermitted(
        new PermittedOptions().setAddress("page.saved"))));



     int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8081);
    server
      .requestHandler(router::accept)
      .listen(portNumber, ar -> {
        if (ar.succeeded()) {
          LOGGER.info("HTTP server running on port " + portNumber);
          startFuture.complete();
        } else {
          LOGGER.error("Could not start a HTTP server", ar.cause());
          startFuture.fail(ar.cause());
        }
      });

  }

  private void tvShowsHandler(RoutingContext context){

    Connection conn = null;
    conn = r.connection().hostname("localhost").port(28015).connect();

    
    
    Cursor<HashMap> results = r.db("vertx_rethink").table("tv_shows").filter(row -> row.g("visited").eq("false"))
                              .run(conn);


    JsonArray events = new JsonArray();
    for(HashMap o : results){
     JsonObject event = new JsonObject();
                      event
                      .put("name", o.get("name")) // <2>
                      .put("tv_show",o.get("tv_show"))
                      .put("success", true); 
          events.add(event);
      r.db("vertx_rethink").table("tv_shows").get(o.get("id")).update(r.hashMap("visited", "true"))
                              .run(conn);

    }
    // JsonObject event = new JsonObject()
      //                .put("name", "name") // <2>
        //              .put("tv_show","tv_show")
          //            .put("success", true); 
            //      events.add(event);

      context.response().setStatusCode(200);
      context.response().putHeader("Content-Type", "application/json");
      context.response().end(events.encode());
  }

  // tag::apiDeletePage[]
  private void apiDeletePage(RoutingContext context) {
    if (context.user().principal().getBoolean("canDelete", false)) {
      int id = Integer.valueOf(context.request().getParam("id"));
      dbService.deletePage(id, reply -> {
        handleSimpleDbReply(context, reply);
      });
    } else {
      context.fail(401);
    }
  }
  // end::apiDeletePage[]

  private void handleSimpleDbReply(RoutingContext context, AsyncResult<Void> reply) {
    if (reply.succeeded()) {
      context.response().setStatusCode(200);
      context.response().putHeader("Content-Type", "application/json");
      context.response().end(new JsonObject().put("success", true).encode());
    } else {
      context.response().setStatusCode(500);
      context.response().putHeader("Content-Type", "application/json");
      context.response().end(new JsonObject()
        .put("success", false)
        .put("error", reply.cause().getMessage()).encode());
    }
  }

  private void apiUpdatePage(RoutingContext context) {
    if (context.user().principal().getBoolean("canUpdate", false)) {
      int id = Integer.valueOf(context.request().getParam("id"));
      JsonObject page = context.getBodyAsJson();
      if (!validateJsonPageDocument(context, page, "markdown")) {
        return;
      }
      dbService.savePage(id, page.getString("markdown"), reply -> {
        handleSimpleDbReply(context, reply);
      });
    } else {
      context.fail(401);
    }
  }

  private boolean validateJsonPageDocument(RoutingContext context, JsonObject page, String... expectedKeys) {
    if (!Arrays.stream(expectedKeys).allMatch(page::containsKey)) {
      LOGGER.error("Bad page creation JSON payload: " + page.encodePrettily() + " from " + context.request().remoteAddress());
      context.response().setStatusCode(400);
      context.response().putHeader("Content-Type", "application/json");
      context.response().end(new JsonObject()
        .put("success", false)
        .put("error", "Bad request payload").encode());
      return false;
    }
    return true;
  }

  private void apiCreatePage(RoutingContext context) {
    if (context.user().principal().getBoolean("canCreate", false)) {
      JsonObject page = context.getBodyAsJson();
      if (!validateJsonPageDocument(context, page, "name", "markdown")) {
        return;
      }
      dbService.createPage(page.getString("name"), page.getString("markdown"), reply -> {
        if (reply.succeeded()) {
          context.response().setStatusCode(201);
          context.response().putHeader("Content-Type", "application/json");
          context.response().end(new JsonObject().put("success", true).encode());
        } else {
          context.response().setStatusCode(500);
          context.response().putHeader("Content-Type", "application/json");
          context.response().end(new JsonObject()
            .put("success", false)
            .put("error", reply.cause().getMessage()).encode());
        }
      });
    } else {
      context.fail(401);
    }
  }

  private void apiGetPage(RoutingContext context) {
    int id = Integer.valueOf(context.request().getParam("id"));
    dbService.fetchPageById(id, reply -> {
      JsonObject response = new JsonObject();
      if (reply.succeeded()) {
        JsonObject dbObject = reply.result();
        if (dbObject.getBoolean("found")) {
          JsonObject payload = new JsonObject()
            .put("name", dbObject.getString("name"))
            .put("id", dbObject.getInteger("id"))
            .put("markdown", dbObject.getString("content"))
            .put("html", Processor.process(dbObject.getString("content")));
          response
            .put("success", true)
            .put("page", payload);
          context.response().setStatusCode(200);
        } else {
          context.response().setStatusCode(404);
          response
            .put("success", false)
            .put("error", "There is no page with ID " + id);
        }
      } else {
        response
          .put("success", false)
          .put("error", reply.cause().getMessage());
        context.response().setStatusCode(500);
      }
      context.response().putHeader("Content-Type", "application/json");
      context.response().end(response.encode());
    });
  }

  private void apiRoot(RoutingContext context) {
    dbService.fetchAllPagesData(reply -> {
      JsonObject response = new JsonObject();
      if (reply.succeeded()) {
        List<JsonObject> pages = reply.result()
          .stream()
          .map(obj -> new JsonObject()
            .put("id", obj.getInteger("ID"))
            .put("name", obj.getString("NAME")))
          .collect(Collectors.toList());
        response
          .put("success", true)
          .put("pages", pages);
        context.response().setStatusCode(200);
        context.response().putHeader("Content-Type", "application/json");
        context.response().end(response.encode());
      } else {
        response
          .put("success", false)
          .put("error", reply.cause().getMessage());
        context.response().setStatusCode(500);
        context.response().putHeader("Content-Type", "application/json");
        context.response().end(response.encode());
      }
    });
  }

  // tag::indexHandler[]
  private void indexHandler(RoutingContext context) {
    context.user().isAuthorized("create", res -> {  // <1>
      boolean canCreatePage = res.succeeded() && res.result();  // <2>
      dbService.fetchAllPages(reply -> {
        if (reply.succeeded()) {
          context.put("title", "Wiki home");
          context.put("pages", reply.result().getList());
          context.put("canCreatePage", canCreatePage);  // <3>
          context.put("username", context.user().principal().getString("username"));  // <4>
          templateEngine.render(context, "templates", "/index.ftl", ar -> {
            if (ar.succeeded()) {
              context.response().putHeader("Content-Type", "text/html");
              context.response().end(ar.result());
            } else {
              context.fail(ar.cause());
            }
          });
        } else {
          context.fail(reply.cause());
        }
      });
    });
  }
  // end::indexHandler[]

  private void pageRenderingHandler(RoutingContext context) {
    context.user().isAuthorized("update", updateResponse -> {
      boolean canSavePage = updateResponse.succeeded() && updateResponse.result();
      context.user().isAuthorized("delete", deleteResponse -> {
        boolean canDeletePage = deleteResponse.succeeded() && deleteResponse.result();

        String requestedPage = context.request().getParam("page");
        dbService.fetchPage(requestedPage, reply -> {
          if (reply.succeeded()) {

            JsonObject payLoad = reply.result();
            boolean found = payLoad.getBoolean("found");
            String rawContent = payLoad.getString("rawContent", EMPTY_PAGE_MARKDOWN);
            context.put("title", requestedPage);
            context.put("id", payLoad.getInteger("id", -1));
            context.put("newPage", found ? "no" : "yes");
            context.put("rawContent", rawContent);
            context.put("content", Processor.process(rawContent));
            context.put("timestamp", new Date().toString());
            context.put("username", context.user().principal().getString("username"));
            context.put("canSavePage", canSavePage);
            context.put("canDeletePage", canDeletePage);

            templateEngine.render(context, "templates", "/page.ftl", ar -> {
              if (ar.succeeded()) {
                context.response().putHeader("Content-Type", "text/html");
                context.response().end(ar.result());
              } else {
                context.fail(ar.cause());
              }
            });

          } else {
            context.fail(reply.cause());
          }
        });

      });
    });
  }

  // tag::loginHandler[]
  private void loginHandler(RoutingContext context) {
    context.put("title", "Login");
    templateEngine.render(context, "templates", "/login.ftl", ar -> {
      if (ar.succeeded()) {
        context.response().putHeader("Content-Type", "text/html");
        context.response().end(ar.result());
      } else {
        context.fail(ar.cause());
      }
    });
  }
  // end::loginHandler[]

  private void pageUpdateHandler(RoutingContext context) {
    boolean pageCreation = "yes".equals(context.request().getParam("newPage"));
    context.user().isAuthorized(pageCreation ? "create" : "update", res -> {
      if (res.succeeded() && res.result()) {

        String title = context.request().getParam("title");

        Handler<AsyncResult<Void>> handler = reply -> {
          if (reply.succeeded()) {
            context.response().setStatusCode(303);
            context.response().putHeader("Location", "/wiki/" + title);
            context.response().end();
          } else {
            context.fail(reply.cause());
          }
        };

        String markdown = context.request().getParam("markdown");
        if (pageCreation) {
          dbService.createPage(title, markdown, handler);
        } else {
          dbService.savePage(Integer.valueOf(context.request().getParam("id")), markdown, handler);
        }

      } else {
        context.response().setStatusCode(403).end();
      }
    });
  }

  private void pageCreateHandler(RoutingContext context) {
    String pageName = context.request().getParam("name");
    String location = "/wiki/" + pageName;
    if (pageName == null || pageName.isEmpty()) {
      location = "/";
    }
    context.response().setStatusCode(303);
    context.response().putHeader("Location", location);
    context.response().end();
  }

  // tag::pageDeletionHandler[]
  private void pageDeletionHandler(RoutingContext context) {
    context.user().isAuthorized("delete", res -> {
      if (res.succeeded() && res.result()) {

        // Original code:
        dbService.deletePage(Integer.valueOf(context.request().getParam("id")), reply -> {
          if (reply.succeeded()) {
            context.response().setStatusCode(303);
            context.response().putHeader("Location", "/");
            context.response().end();
          } else {
            context.fail(reply.cause());
          }
        });

      } else {
        context.response().setStatusCode(403).end();
      }
    });
  }
  // end::pageDeletionHandler[]

  private void backupHandler(RoutingContext context) {
    context.user().isAuthorized("role:writer", res -> {
      if (res.succeeded() && res.result()) {
        dbService.fetchAllPagesData(reply -> {
          if (reply.succeeded()) {

            JsonArray filesObject = new JsonArray();
            JsonObject payload = new JsonObject() // <1>
              .put("files", filesObject)
              .put("language", "plaintext")
              .put("title", "vertx-wiki-backup")
              .put("public", true);

            reply
              .result()
              .forEach(page -> {
                JsonObject fileObject = new JsonObject(); // <2>
                fileObject.put("name", page.getString("NAME"));
                fileObject.put("content", page.getString("CONTENT"));
                filesObject.add(fileObject);
              });

            webClient.post(443, "snippets.glot.io", "/snippets") // <3>
              .putHeader("Content-Type", "application/json")
              .as(BodyCodec.jsonObject()) // <4>
              .sendJsonObject(payload, ar -> {  // <5>
                if (ar.succeeded()) {
                  HttpResponse<JsonObject> response = ar.result();
                  if (response.statusCode() == 200) {
                    String url = "https://glot.io/snippets/" + response.body().getString("id");
                    context.put("backup_gist_url", url);  // <6>
                    indexHandler(context);
                  } else {
                    StringBuilder message = new StringBuilder()
                      .append("Could not backup the wiki: ")
                      .append(response.statusMessage());
                    JsonObject body = response.body();
                    if (body != null) {
                      message.append(System.getProperty("line.separator"))
                        .append(body.encodePrettily());
                    }
                    LOGGER.error(message.toString());
                    context.fail(502);
                  }
                } else {
                  Throwable err = ar.cause();
                  LOGGER.error("HTTP Client error", err);
                  context.fail(err);
                }
              });

          } else {
            context.fail(reply.cause());
          }
        });
      } else {
        context.response().setStatusCode(403).end();
      }
    });
  }
}