package blockagent;

import blockagent.game.Agent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模組進入點：建立 Agent、啟動 MCP 伺服器、掛上 Fabric 事件。
 */
public final class BlockAgentMod implements ClientModInitializer {
	public static final String MOD_ID = "blockagent";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}

	@Override
	public void onInitializeClient() {
		var loader = FabricLoader.getInstance();
		Config config = Config.load(loader.getConfigDir().resolve("blockagent.json"));
		Agent agent;
		try {
			agent = new Agent(config, loader.getGameDir());
		} catch (Exception e) {
			LOG.error("BlockAgent 初始化失敗", e);
			return;
		}
		if (!blockagent.game.Bari.mixinTargetsPresent()) {
			LOG.error("找不到 Baritone 的內部類別：裝的可能是混淆版(baritone-standalone-fabric)。請改裝 baritone-unoptimized-fabric，"
				+ "不然尋路時不會避開別人的方塊");
		}
				agent.control.registerKeys();
		agent.hud.register();

		ClientTickEvents.END_CLIENT_TICK.register(agent::onEndTick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> agent.onJoin(mc));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> agent.onDisconnect(mc));
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> agent.chat.onPlayerChat(message, signed, sender, params));
		ClientReceiveMessageEvents.GAME.register(agent.chat::onGameMessage);
		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			agent.tracker.onEntityLoad(entity);
			agent.archery.onEntityLoad(entity, agent.tick());
		});
		// 天然方塊的判斷會快取，tag 重新載入(換世界、/reload)時清掉(M15)
		net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> agent.guard.clearCache());
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			try {
				agent.startMcp();
			} catch (Exception e) {
				LOG.error("MCP 伺服器啟動失敗", e);
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> agent.shutdown());
		LOG.info("BlockAgent {} 載入", version());
	}
}
