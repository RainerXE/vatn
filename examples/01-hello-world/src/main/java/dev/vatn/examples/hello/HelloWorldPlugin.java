package dev.vatn.examples.hello;

import dev.vatn.api.VHttpRoutes;
import dev.vatn.api.VHttpService;
import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;

public class HelloWorldPlugin implements VNodePlugin {

    @Override public String getId()      { return "dev.vatn.examples.hello-world"; }
    @Override public String getName()    { return "Hello World"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        ctx.register("/hello", new HelloService());
    }

    private static class HelloService implements VHttpService {
        @Override
        public void routing(VHttpRoutes routes) {
            routes.get("/", (req, res) -> res.send("Hello from VATN!"));
            routes.get("/json", (req, res) -> res.sendJson("""
                    {"message":"Hello from VATN!","runtime":"vatn-core","threads":"virtual"}
                    """));
        }
    }
}
