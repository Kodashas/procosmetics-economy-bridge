package io.github.kodashas.cosmeticsbridge;

import java.util.Objects;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import se.filledev.procosmetics.api.ProCosmetics;
import se.filledev.procosmetics.api.ProCosmeticsProvider;
import su.nightexpress.excellenteconomy.EconomyPlugin;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

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
        boolean debug = getConfig().getBoolean("debug", false);

        // Resolved here, before registering: ProCosmetics' register() only stores the provider
        // and never calls hook(), so a bad currency-id has to fail the enable right here or it
        // would surface as a null currency the first time a player opens the cosmetics menu.
        ExcellentCurrency currency = excellentEconomy.currencyById(currencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "ExcellentEconomy has no currency with id '" + currencyId + "'."));

        proCosmetics.getEconomyManager().register(new ExcellentCurrencyEconomyProvider(
                excellentEconomy,
                currency,
                currencyName,
                purchaseSuccessMessage,
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
