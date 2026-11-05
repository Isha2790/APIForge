-- Appends one usage event and caps the stream length so Redis memory stays bounded.
-- KEYS[1] = stream, ARGV[1] = approximate max length, ARGV[2..] = field/value pairs.
redis.call('XADD', KEYS[1], 'MAXLEN', '~', ARGV[1], '*', unpack(ARGV, 2))
return 1
