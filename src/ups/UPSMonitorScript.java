package ups;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * Watches every market that has a Planetary Shield and mitigates the effects of
 * tactical/saturation bombardment when the shield was functional at the moment
 * of the strike.
 *
 * There is no cancellable pre-bombardment hook in vanilla (the AI crisis path
 * calls MarketCMD.doBombardment() directly, and the listeners fire post-hoc on
 * the player path only), so this works reactively:
 *
 *  - each tick (~0.1 days) it snapshots per-market state: industry disruption
 *    days, recent-unrest penalty, size, pollution, the RECENTLY_BOMBARDED flag
 *    and its expiry, and whether the shield was functional;
 *  - a bombardment is detected by the RECENTLY_BOMBARDED flag newly appearing
 *    (or its expiry jumping - repeat strikes), or by 2+ industries getting
 *    disrupted simultaneously while the flag is set (raids only ever disrupt a
 *    single industry, bombardments hit many);
 *  - if the shield was functional at the previous snapshot, the deltas are
 *    partially rolled back per config: disruption durations and unrest scaled
 *    down, pollution removed, colony size restored.
 *
 * Decivilization is prevented separately (and proactively) by keeping the
 * vanilla story-critical memory flag on shielded markets: all three bombardment
 * code paths check Misc.isStoryCritical() and downgrade "destroy" to a size
 * reduction, which the rollback above then also restores. The same flag stops
 * stability-collapse deciv in DecivTracker. The flag is managed with our own
 * marker so a flag set by vanilla/other mods is never clobbered, and is removed
 * the moment the shield stops being functional.
 *
 * Because the bombardment itself disrupts the shield (it has no
 * no_saturation_bombardment tag and EXTREME disruptDanger), a shielded colony
 * absorbs the first strike and is then vulnerable until the shield repairs -
 * the mod's built-in balance valve.
 */
public class UPSMonitorScript implements EveryFrameScript {

	private static final Logger log = Global.getLogger(UPSMonitorScript.class);

	/** Our bookkeeping marker: we set $story_critical on this market (vs. vanilla having set it). */
	public static final String SET_STORY_CRITICAL_KEY = "$ups_setStoryCritical";

	/** Ignore disruption deltas smaller than this (days) - noise/decay guard. */
	private static final float MIN_DISRUPT_DELTA = 5f;

	private final IntervalUtil interval = new IntervalUtil(0.08f, 0.12f);

	private static class Snap {
		int size;
		boolean shieldFunctional;
		boolean bombFlag;
		float bombExpire;
		boolean pollution;
		int unrest;
		final Map<String, Float> disrupted = new HashMap<String, Float>();
	}

	private final Map<String, Snap> snaps = new HashMap<String, Snap>();

	@Override
	public boolean isDone() {
		return false;
	}

	@Override
	public boolean runWhilePaused() {
		return false;
	}

	@Override
	public void advance(float amount) {
		float days = Global.getSector().getClock().convertToDays(amount);
		interval.advance(days);
		if (!interval.intervalElapsed()) return;

		Set<String> seen = new HashSet<String>();

		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			Industry shield = market.getIndustry(Industries.PLANETARYSHIELD);
			if (shield == null) {
				cleanupStoryCritical(market);
				continue;
			}
			if (UPSConfig.playerMarketsOnly() && !market.isPlayerOwned()) {
				cleanupStoryCritical(market);
				continue;
			}

			seen.add(market.getId());

			maintainStoryCritical(market, shield);

			Snap prev = snaps.get(market.getId());
			Snap cur = takeSnap(market, shield);

			if (prev != null && prev.shieldFunctional && detectBombardment(prev, cur)) {
				mitigate(market, prev);
				// re-snapshot so the mitigated state is the new baseline
				cur = takeSnap(market, shield);
			}

			snaps.put(market.getId(), cur);
		}

		// prune markets that lost their shield, decivilized, or left the economy
		snaps.keySet().retainAll(seen);
	}

	// ---- story-critical management (deciv block) ----

	private void maintainStoryCritical(MarketAPI market, Industry shield) {
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		boolean shouldProtect = UPSConfig.blockDeciv() && shield.isFunctional();

		if (shouldProtect) {
			if (!mem.getBoolean(MemFlags.STORY_CRITICAL)) {
				mem.set(MemFlags.STORY_CRITICAL, true);
				mem.set(SET_STORY_CRITICAL_KEY, true);
				if (UPSConfig.debugLogging()) {
					log.info("[UPS] " + market.getName() + ": shield functional, deciv protection on");
				}
			}
		} else {
			cleanupStoryCritical(market);
		}
	}

	private void cleanupStoryCritical(MarketAPI market) {
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		if (mem.getBoolean(SET_STORY_CRITICAL_KEY)) {
			mem.unset(MemFlags.STORY_CRITICAL);
			mem.unset(SET_STORY_CRITICAL_KEY);
			if (UPSConfig.debugLogging()) {
				log.info("[UPS] " + market.getName() + ": deciv protection off");
			}
		}
	}

	// ---- snapshot & detection ----

	private Snap takeSnap(MarketAPI market, Industry shield) {
		Snap s = new Snap();
		s.size = market.getSize();
		s.shieldFunctional = shield.isFunctional();
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		s.bombFlag = mem.getBoolean(MemFlags.RECENTLY_BOMBARDED);
		s.bombExpire = safeExpire(mem, MemFlags.RECENTLY_BOMBARDED);
		s.pollution = market.hasCondition(Conditions.POLLUTION);
		s.unrest = RecentUnrest.getPenalty(market);
		for (Industry ind : market.getIndustries()) {
			s.disrupted.put(ind.getId(), ind.getDisruptedDays());
		}
		return s;
	}

	private float safeExpire(MemoryAPI mem, String key) {
		try {
			return mem.getExpire(key);
		} catch (Throwable t) {
			return -1f;
		}
	}

	private boolean detectBombardment(Snap prev, Snap cur) {
		if (!cur.bombFlag) return false;

		// flag newly set, or its 30-day expiry re-armed by a repeat strike
		if (!prev.bombFlag) return true;
		if (cur.bombExpire > prev.bombExpire + 0.5f) return true;

		// fallback signature: raids disrupt exactly one industry, bombardments hit many
		int spiked = 0;
		for (Map.Entry<String, Float> e : cur.disrupted.entrySet()) {
			Float before = prev.disrupted.get(e.getKey());
			float b = before == null ? 0f : before;
			if (e.getValue() - b > MIN_DISRUPT_DELTA) spiked++;
		}
		return spiked >= 2;
	}

	// ---- mitigation ----

	private void mitigate(MarketAPI market, Snap prev) {
		boolean didAnything = false;

		// 1) restore colony size (saturation strike on a shielded world)
		if (UPSConfig.blockSizeReduction() && market.getSize() < prev.size) {
			restoreSize(market, prev.size);
			didAnything = true;
		}

		// 2) scale down newly added industry disruption
		float durMult = UPSConfig.disruptionMult();
		if (durMult < 1f) {
			for (Industry ind : market.getIndustries()) {
				Float before = prev.disrupted.get(ind.getId());
				float b = before == null ? 0f : before;
				float delta = ind.getDisruptedDays() - b;
				if (delta > MIN_DISRUPT_DELTA) {
					ind.setDisrupted(b + delta * durMult, false);
					didAnything = true;
					if (UPSConfig.debugLogging()) {
						log.info("[UPS] " + market.getName() + ": " + ind.getCurrentName()
								+ " disruption " + (int) (b + delta) + "d -> "
								+ (int) (b + delta * durMult) + "d");
					}
				}
			}
		}

		// 3) refund part of the stability hit
		float unrestMult = UPSConfig.unrestMult();
		int deltaU = RecentUnrest.getPenalty(market) - prev.unrest;
		if (unrestMult < 1f && deltaU > 0) {
			int refund = deltaU - Math.round(deltaU * unrestMult);
			if (refund > 0) {
				RecentUnrest.get(market).counter(refund, "Planetary shield");
				didAnything = true;
			}
		}

		// 4) no pollution: the bombs hit the shield, not the biosphere
		if (UPSConfig.blockPollution() && !prev.pollution && market.hasCondition(Conditions.POLLUTION)) {
			market.removeCondition(Conditions.POLLUTION);
			didAnything = true;
		}

		// 5) absorbing the strike knocks the shield generator offline.
		// Saturation strikes already disrupt it (vanilla targeting; halved above) -
		// setDisrupted with useMax makes this a floor. Tactical strikes never touch
		// the shield in vanilla, so without this they'd be absorbed for free forever.
		float offlineDays = UPSConfig.shieldOfflineDays();
		if (didAnything && offlineDays > 0) {
			Industry shield = market.getIndustry(Industries.PLANETARYSHIELD);
			if (shield != null) {
				shield.setDisrupted(offlineDays, true);
			}
		}

		if (didAnything) {
			if (UPSConfig.debugLogging()) {
				log.info("[UPS] " + market.getName() + ": bombardment absorbed (shield was functional)");
			}
			if (UPSConfig.showMessage() && market.isPlayerOwned()) {
				Global.getSector().getCampaignUI().addMessage(
						"The planetary shield over " + market.getName()
								+ " absorbed the brunt of the bombardment",
						Misc.getPositiveHighlightColor());
			}
		}
	}

	/** Inverse of CoreImmigrationPluginImpl.reduceMarketSize(). */
	private void restoreSize(MarketAPI market, int targetSize) {
		int cur = market.getSize();
		market.removeCondition("population_" + cur);
		if (!market.hasCondition("population_" + targetSize)) {
			market.addCondition("population_" + targetSize);
		}
		market.setSize(targetSize);
		market.getPopulation().setWeight(
				CoreImmigrationPluginImpl.getWeightForMarketSizeStatic(targetSize));
		market.getPopulation().normalize();
		market.reapplyConditions();
		market.reapplyIndustries();
		if (UPSConfig.debugLogging()) {
			log.info("[UPS] " + market.getName() + ": size restored " + cur + " -> " + targetSize);
		}
	}
}
