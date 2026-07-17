-- wrk Lua script for POST /echo benchmark
wrk.method = "POST"
wrk.body   = '{"msg":"hello"}'
wrk.headers["Content-Type"] = "application/json"
