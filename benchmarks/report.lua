-- Run from the project root. IDs are intentionally reused; no deduplication exists yet.
local file = assert(io.open("examples/batch.json", "rb"))
wrk.method = "POST"
wrk.body = file:read("*all")
file:close()
wrk.headers["Content-Type"] = "application/json"
