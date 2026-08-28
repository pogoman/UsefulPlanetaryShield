package ups;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

public class UPSModPlugin extends BaseModPlugin {

	@Override
	public void onGameLoad(boolean newGame) {
		// transient: cleared automatically on save load, never written to the save
		Global.getSector().addTransientScript(new UPSMonitorScript());
	}
}
