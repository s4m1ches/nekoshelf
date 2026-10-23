--[[
E-ink full refresh bridge for the Meebook E6 (Rockchip RK3566).

KOReader's Android build has EPD controllers for some Meebook models, but not
for the E6, so on that device every "full refresh" (every N pages, on chapter
boundaries, the full_refresh gesture) is a plain repaint with no flash.

This patch:
  * makes KOReader treat the screen as e-ink (unlocks Settings → Screen →
    E-ink settings → Full refresh rate, chapter-boundary/image flashes);
  * routes full refreshes to the firmware's android.os.EinkManager
    (system service "eink") → sendOneFullFrame().

Partial refreshes are left to the firmware's per-app mode (Regal).
The patch disables itself on any other device, or if KOReader gains native
support for the E6, or if the system service/method is missing.
]]

local ok, android = pcall(require, "android")
if not ok or type(android) ~= "table" or not android.prop or not android.jni then return end
if (android.prop.brand or ""):lower() ~= "meebook" then return end -- device gate: brand string reported by the firmware

local logger = require("logger")

local native_eink = android.isEink()
if native_eink then
    logger.info("nekoshelf-eink: KOReader supports this panel natively, patch inactive")
    return
end

local JNI = android.jni

-- Runs fn(env, mgr, method_id) with the EinkManager instance and the jmethodID
-- of `method` ("()V"). Returns true when the call path is valid and no Java
-- exception was raised.
local function withEinkMethod(method, invoke)
    return JNI:context(android.app.activity.vm, function(jni)
        local env = jni.env
        local function clearException()
            if env[0].ExceptionCheck(env) ~= 0 then
                env[0].ExceptionClear(env)
                return true
            end
            return false
        end

        local name = env[0].NewStringUTF(env, "eink")
        local mgr = jni:callObjectMethod(android.app.activity.clazz, "getSystemService",
            "(Ljava/lang/String;)Ljava/lang/Object;", name)
        env[0].DeleteLocalRef(env, name)
        if clearException() or mgr == nil then return false end

        local clazz = env[0].GetObjectClass(env, mgr)
        local mid = env[0].GetMethodID(env, clazz, method, "()V")
        env[0].DeleteLocalRef(env, clazz)
        if clearException() or mid == nil then
            env[0].DeleteLocalRef(env, mgr)
            return false
        end

        if invoke then
            env[0].CallVoidMethod(env, mgr, mid)
        end
        local failed = clearException()
        env[0].DeleteLocalRef(env, mgr)
        return not failed
    end)
end

local probe_ok, probe_res = pcall(withEinkMethod, "sendOneFullFrame", false)
if not (probe_ok and probe_res) then
    logger.warn("nekoshelf-eink: EinkManager.sendOneFullFrame unavailable, patch inactive", probe_res)
    return
end

local function fullFrame()
    local call_ok, res = pcall(withEinkMethod, "sendOneFullFrame", true)
    if not (call_ok and res) then
        logger.warn("nekoshelf-eink: sendOneFullFrame failed", res)
    end
end

-- Pretend to be a Rockchip e-ink device. Must run before ffi/framebuffer_android
-- is first required (it caches these values at load time) — hence priority 1.
android.isEink = function() return true, "rockchip" end
android.isEinkFull = function() return false end
-- full, partial, full_ui, partial_ui, fast, delay_page, delay_ui, delay_fast
android.getEinkConstants = function() return 1, 2, 3, 4, 5, 0, 0, 0 end
android.einkUpdate = function() end -- full refreshes are handled below

local framebuffer = require("ffi/framebuffer_android")
-- Request the full (GC16) frame first, then post the new content, so the flash
-- shows the page being drawn rather than the previous one.
function framebuffer:refreshFullImp(x, y, w, h) -- luacheck: ignore
    fullFrame()
    self:_updateWindow()
end

logger.info("nekoshelf-eink: full refresh bridged to EinkManager.sendOneFullFrame")
