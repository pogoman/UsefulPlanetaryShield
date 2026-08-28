package ups;

import lunalib.lunaSettings.LunaSettings;

/**
 * Thin wrapper around LunaLib's settings API. This class references LunaLib
 * types directly, so it must ONLY be loaded when LunaLib is present - callers
 * gate every use behind {@link UPSConfig#lunaAvailable()}, which keeps the
 * classloader from ever touching this class in a LunaLib-less install.
 */
class LunaConfigBridge {

	static Float getFloat(String key) {
		return LunaSettings.getFloat(UPSConfig.MOD_ID, key);
	}

	static Boolean getBoolean(String key) {
		return LunaSettings.getBoolean(UPSConfig.MOD_ID, key);
	}
}
