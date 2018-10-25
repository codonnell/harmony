# Rate Limiting

Prefer to be unopinionated, but still want avoiding rate limit violations to be convenient.

## Operation

Each url route has its own independent rate limit. A route includes major
parameters (channel id, guild id, and webhook id), so sending messages to two
different channels would be enforced by two independent rate limits.

Every API response comes with rate limit headers:
* `X-RateLimit-Limit` - The number of requests that can be made
* `X-RateLimit-Remaining` - The number of remaining requests that can be made
* `X-RateLimit-Reset` - Epoch-time (in seconds) at which the rate limit resets

With the exception of a 429 indicating a violation of the global rate limit,
which returns just:
* `X-RateLimit-Global` - Indicates the above headers are
  for the global rate limit
  
In the event that the rate limit is exceeded, discord returns a 429 response with a JSON body containing the following keys:
* `message` (string) - A message saying you are being rate limited
* `retry_after` (integer) - Number of milliseconds to wait before submitting another request
* `global` (boolean) - Indicator of whether the 429 was caused by a global rate limit violation

## API Options

There are a few approaches we could take to preventing requests that would be
rejected due to the rate limit.

### Synchronous wait (queue? bounded?)

We could block on requests that are attempted when 0 requests are available or before `retry_after` has expired for a 429. These would likely need to go into a queue of some kind rather than just sleeping a thread until the proper time. This is to avoid a torrent of requests just as new requests are allowed again.

This approach has the advantage of simplicity. The caller only resumes control after its request has been sent; they don't need to deal with "callback hell." This has the disadvantage of blocking a thread potentially for quite a long time.

In addition, we are now dealing with queuing and need to handle the case that the queue fills up.

### Wait with callback

This is similar to the above approach, but it does not block a thread while the request waits to be sent.

### Return error immediately

This forces the client to reschedule the request herself, putting additional burden on her. This _does_ represent a less opinionated approach. This may be best, at least to start. With the advantage of experience perhaps another approach will prove more advantageous.

We can also expose this as a configuration option. The user may choose to just send the request anyways, and I don't think we should forcibly stop them.

## Rate Limiter Component

Needs to track request limit, requests remaining, and reset time for each endpoint and for the global rate limit. Also needs to track `retry_after` on 429 responses. Also needs to determine if a request will violate the global or endpoint-specific rate limit.

The API should include:

* `(track! rate-limiter response)`, which updates the tracking information using the headers (and body for a 429) of the given response.
* `(limited? rate-limiter request)`, which determines whether a request would violate a rate limit. In the event that the request would violate a rate limit, returns data about when to retry.
