package ups;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.PlanetaryShield;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Strings;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Drop-in replacement for the vanilla Planetary Shield industry plugin
 * (swapped in via data/campaign/industries.csv).
 *
 * By default the vanilla x3 ground-defense bonus (and its alpha-core /
 * improvement extensions) is stripped - the shield stops bombs, not marines.
 * Everything else (upkeep, meteor suppression, visuals) is vanilla. Flip
 * ups_groundDefenseBonus to restore stock behavior entirely.
 *
 * The bombardment absorption itself lives in {@link UPSMonitorScript}.
 */
public class UPSPlanetaryShield extends PlanetaryShield {

	private boolean groundDefEnabled() {
		return UPSConfig.groundDefenseBonus();
	}

	@Override
	public void apply() {
		super.apply();
		if (!groundDefEnabled()) {
			// strip the base, alpha-core, and improvement ground-defense mults
			market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(getModId());
			market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(getModId(1));
			market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(getModId(2));
		}
	}

	@Override
	protected void applyAlphaCoreModifiers() {
		if (groundDefEnabled()) super.applyAlphaCoreModifiers();
	}

	@Override
	protected void applyImproveModifiers() {
		if (groundDefEnabled()) {
			super.applyImproveModifiers();
		} else {
			market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(getModId(2));
		}
	}

	@Override
	public boolean canImprove() {
		// with the ground-defense bonus stripped, improving would do nothing
		return groundDefEnabled();
	}

	@Override
	protected boolean hasPostDemandSection(boolean hasDemand, IndustryTooltipMode mode) {
		return true;
	}

	@Override
	protected void addPostDemandSection(TooltipMakerAPI tooltip, boolean hasDemand, IndustryTooltipMode mode) {
		if (groundDefEnabled()) {
			super.addPostDemandSection(tooltip, hasDemand, mode);
		}

		float opad = 10f;
		Color h = Misc.getHighlightColor();

		int through = Math.round(UPSConfig.disruptionMult() * 100f);
		int unrest = Math.round(UPSConfig.unrestMult() * 100f);

		tooltip.addPara("While the shield is operational, orbital bombardment is largely absorbed: "
				+ "industry disruption is reduced to %s and stability loss to %s of normal"
				+ (UPSConfig.blockPollution() ? ", and no pollution results" : "") + ".",
				opad, h, through + "%", unrest + "%");

		if (UPSConfig.blockSizeReduction() || UPSConfig.blockDeciv()) {
			String what;
			if (UPSConfig.blockSizeReduction() && UPSConfig.blockDeciv()) {
				what = "reduced in size or destroyed";
			} else if (UPSConfig.blockDeciv()) {
				what = "destroyed";
			} else {
				what = "reduced in size";
			}
			tooltip.addPara("The colony can not be %s by bombardment while the shield is operational.",
					opad, h, what);
		}

		int offlineDays = Math.round(UPSConfig.shieldOfflineDays());
		if (offlineDays > 0) {
			tooltip.addPara("The shield generator itself is knocked offline for at least %s days "
					+ "by a bombardment it absorbs, leaving the colony unprotected until repairs "
					+ "are complete.", opad, h, "" + offlineDays);
		} else {
			tooltip.addPara("The shield generator itself is disrupted by a saturation bombardment "
					+ "it absorbs, leaving the colony unprotected until repairs are complete.", opad);
		}
	}

	@Override
	protected void addAlphaCoreDescription(TooltipMakerAPI tooltip, AICoreDescriptionMode mode) {
		if (groundDefEnabled()) {
			super.addAlphaCoreDescription(tooltip, mode);
			return;
		}

		// vanilla text minus the ground-defense claim
		float opad = 10f;
		Color highlight = Misc.getHighlightColor();

		String pre = "Alpha-level AI core currently assigned. ";
		if (mode == AICoreDescriptionMode.MANAGE_CORE_DIALOG_LIST || mode == AICoreDescriptionMode.INDUSTRY_TOOLTIP) {
			pre = "Alpha-level AI core. ";
		}

		if (mode == AICoreDescriptionMode.INDUSTRY_TOOLTIP) {
			CommoditySpecAPI coreSpec = Global.getSettings().getCommoditySpec(aiCoreId);
			TooltipMakerAPI text = tooltip.beginImageWithText(coreSpec.getIconName(), 48);
			text.addPara(pre + "Reduces upkeep cost by %s. Reduces demand by %s unit.", 0f, highlight,
					"" + (int) ((1f - UPKEEP_MULT) * 100f) + "%", "" + DEMAND_REDUCTION);
			tooltip.addImageWithText(opad);
			return;
		}

		tooltip.addPara(pre + "Reduces upkeep cost by %s. Reduces demand by %s unit.", opad, highlight,
				"" + (int) ((1f - UPKEEP_MULT) * 100f) + "%", "" + DEMAND_REDUCTION);
	}
}
