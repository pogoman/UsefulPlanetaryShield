package ups;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;

public class UPSModPlugin extends BaseModPlugin {

	private static final Logger log = Global.getLogger(UPSModPlugin.class);

	@Override
	public void onGameLoad(boolean newGame) {
		migrateExistingShields();

		// transient: cleared automatically on save load, never written to the save
		Global.getSector().addTransientScript(new UPSMonitorScript());
	}

	/**
	 * Shields built before this mod was added were serialized with the vanilla
	 * PlanetaryShield plugin class and keep it on load - industries.csv only
	 * picks the class at construction time. Rebuild those instances so they use
	 * {@link UPSPlanetaryShield}, carrying over AI core, improvement, special
	 * item, and disruption state.
	 *
	 * Shields still under construction are left alone (the in-progress instance
	 * can't be swapped without losing build progress); they get migrated by
	 * this same check on the next game load after completion.
	 */
	private void migrateExistingShields() {
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			Industry old = market.getIndustry(Industries.PLANETARYSHIELD);
			if (old == null || old instanceof UPSPlanetaryShield) continue;
			if (old.isBuilding()) continue;

			String aiCore = old.getAICoreId();
			boolean improved = old.isImproved();
			SpecialItemData special = old.getSpecialItem();
			float disruptedDays = old.getDisruptedDays();

			market.removeIndustry(Industries.PLANETARYSHIELD, null, false);
			market.addIndustry(Industries.PLANETARYSHIELD);

			Industry fresh = market.getIndustry(Industries.PLANETARYSHIELD);
			if (fresh == null) continue; // shouldn't happen
			if (aiCore != null) fresh.setAICoreId(aiCore);
			fresh.setImproved(improved);
			if (special != null) fresh.setSpecialItem(special);
			if (disruptedDays > 0) fresh.setDisrupted(disruptedDays, false);

			market.reapplyIndustries();

			log.info("[UPS] migrated existing Planetary Shield on " + market.getName()
					+ " to " + fresh.getClass().getSimpleName());
		}
	}
}
