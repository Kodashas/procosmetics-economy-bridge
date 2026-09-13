package io.github.kodashas.cosmeticsbridge;

import java.util.Objects;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import se.filledev.procosmetics.api.ProCosmetics;
import se.filledev.procosmetics.api.ProCosmeticsProvider;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;

/**
 * Bridges ProCosmetics' economy to an ExcellentEconomy currency, so cosmetics are
 * bought with that currency instead of ProCosmetics' own internal coins.
 *
 * <p>ProCosmetics only accepts a single {@code EconomyProvider}; registering ours at
 * enable time replaces its built-in one.
 */
public final class EconomyBridgePlugin extends JavaPlugin {

    private static final String DEFAULT_CURRENCY_ID = "coins";
    private static final String DEFAULT_CURRENCY_NAME = "coins";
    private static final String DEFAULT_PURCHASE_SUCCESS =
            "<green>Paid <yellow><amount> <currency></yellow><green>.</green>";
    private static final String DEFAULT_WITHDRAW_FAILURE =
            "<red>Could not take <yellow><amount> <currency></yellow><red>."
                    + " Try again or contact an admin.</red>";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ProCosmetics proCosmetics = Objects.requireNonNull(
                ProCosmeticsProvider.get(),
                "ProCosmetics API provider is not available even though the plugin is marked as a dependency.");

        ExcellentEconomyAPI excellentEconomy = getExcellentEconomyApi();

        String currencyId = getConfig().getString("currency-id", DEFAULT_CURRENCY_ID);

        String currencyName = getConfig().getString("currency-name", DEFAULT_CURRENCY_NAME);

        String purchaseSuccessMessage =
                getConfig().getString("purchase-success-message", DEFAULT_PURCHASE_SUCCESS);
        String withdrawFailureMessage =
                getConfig().getString("withdraw-failure-message", DEFAULT_WITHDRAW_FAILURE);
        boolean debug = getConfig().getBoolean("debug", false);

        // register() makes ProCosmetics call hook(), which resolves the currency, so a bad
        // currency-id fails the enable instead of failing later on a player's purchase.
        proCosmetics.getEconomyManager().register(new ExcellentCurrencyEconomyProvider(
                excellentEconomy,
                currencyId,
                currencyName,
                purchaseSuccessMessage,
                withdrawFailureMessage,
                debug,
                this));

        getLogger().info("ProCosmetics economy is now backed by ExcellentEconomy currency: " + currencyId);
    }

    private ExcellentEconomyAPI getExcellentEconomyApi() {
        Plugin plugin = getServer().getPluginManager().getPlugin("ExcellentEconomy");
        if (!(plugin instanceof EconomyPlugin economyPlugin)) {
            throw new IllegalStateException("ExcellentEconomy plugin was not found or has an unexpected main class.");
        }
        return economyPlugin.getAPI();
    }
}
