-- 1.输入
-- KEYS[1] = 缓存重建互斥锁 Key
-- ARGV[1] = 当前 owner token
-- 2.只有 owner 匹配才删除锁，避免误删其他线程持有的锁
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
