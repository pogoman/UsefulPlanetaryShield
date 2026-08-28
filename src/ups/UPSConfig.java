package ups;

import com.fs.starfarer.api.Global;

/**
 * Central settings accessor. When LunaLib is enabled, values come live from the
 * in-game LunaSettings menu (data/config/LunaSettings.csv). Otherwise they fall
 * back to the bundled data/config/settings.json defaults, so the mod remains
 * fully functional standalone.
 *
 * All LunaLib class references are isolated in {@link LunaConfigBridge}, which
 * is only touched when LunaLib is actually present - so this class loads fine
 * with LunaLib absent.
 */
public class UPSConfig {

	public static final String MOD_ID = "ups";

	private static Boolean lunaEnabled = null;

	public static boolean lunaAvailable() {
		if (lunaEnabled == null) {
			lunaEnabled = Global.getSettings().getModManager().isModEnabled("lunalib");
		}
		return lunaEnabled;
	}

	// ---- raw typed lookups (Luna first, settings.json fallback) ----

	private static float f(String key) {
		if (lunaAvailable()) {
			Float v = LunaConfigBridge.getFloat(key);
			if (v != null) return v;
		}
		return Global.getSettings().getFloat(key);
	}

	private static boolean b(String key, boolean def) {
		if (lunaAvailable()) {
			Boolean v = LunaConfigBridge.getBoolean(key);
			if (v != null) return v;
		}
		try {
			return Global.getSettings().getBoolean(key);
		} catch (Throwable t) {
			return def;
		}
	}

	private static float clamp01(float v) {
		if (v < 0f) return 0f;
		if (v > 1f) return 1f;
		return v;
	}

	// ---- absorption ----

	/** Fraction of bombardment-caused disruption that gets THROUGH the shield (0.5 = halved). */
	public static float disruptionMult() { return clamp01(f("ups_disruptionMult")); }

	/** Fraction of bombardment-caused stability loss that gets through (0.5 = halved). */
	public static float unrestMult() { return clamp01(f("ups_unrestMult")); }

	public static boolean blockPollution() { return b("ups_blockPollution", true); }

	// ---- population protection ----

	public static boolean blockSizeReduction() { return b("ups_blockSizeReduction", true); }
	public static boolean blockDeciv()         { return b("ups_blockDeciv", true); }

	// ---- scope & tradeoffs ----

	public static boolean groundDefenseBonus() { return b("ups_groundDefenseBonus", false); }
	public static boolean playerMarketsOnly()  { return b("ups_playerMarketsOnly", false); }
	public static boolean showMessage()        { return b("ups_showMessage", true); }

	// ---- debug ----

	public static boolean debugLogging() { return b("ups_debugLogging", false); }
}
