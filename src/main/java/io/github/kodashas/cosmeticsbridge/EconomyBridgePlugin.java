package io.github.kodashas.cosmeticsbridge;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import se.filledev.procosmetics.api.ProCosmetics;
import se.filledev.procosmetics.api.ProCosmeticsProvider;
import se.filledev.procosmetics.api.event.PlayerPurchaseCosmeticEvent;
import se.filledev.procosmetics.api.event.PlayerPurchaseGadgetAmmoEvent;
import se.filledev.procosmetics.api.event.PlayerPurchaseTreasureChestEvent;
import se.filledev.procosmetics.api.user.User;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

/**
 * Bridges ProCosmetics' economy to an ExcellentEconomy currency, so cosmetics are
 * bought with that currency instead of ProCosmetics' own internal coins.
 *
 * <p>ProCosmetics only accepts a single {@code EconomyProvider}; registering ours at
 * enable time replaces its built-in one.
 *
 * <p>The purchase message is sent from here, off ProCosmetics' purchase events, rather than
 * from the provider: {@code removeCoinsAsync} is a generic balance operation that an admin
 * {@code /procosmetics remove coins} also goes through, so announcing a payment there told
 * players they had bought something when nothing had been bought. ProCosmetics has no
 * purchase-success message of its own, which is why this one exists at all.
 */
public final class EconomyBridgePlugin extends JavaPlugin implements Listener {

    private static final String DEFAULT_CURRENCY_ID = "coins";
    private static final String DEFAULT_CURRENCY_NAME = "coins";
    private static final String DEFAULT_PURCHASE_SUCCESS =
            "<green>Paid <yellow><amount> <currency></yellow><green>.</green>";

    private String currencyName;
    private String purchaseSuccessMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ProCosmetics proCosmetics = Objects.requireNonNull(
                ProCosmeticsProvider.get(),
                "ProCosmetics API provider is not available even though the plugin is marked as a dependency.");

        ExcellentEconomyAPI excellentEconomy = getExcellentEconomyApi();

        String currencyId = getConfig().getString("currency-id", DEFAULT_CURRENCY_ID);

        currencyName = getConfig().getString("currency-name", DEFAULT_CURRENCY_NAME);

        purchaseSuccessMessage =
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
                debug,
                getLogger(),
                Bukkit::getPlayer,
                this::runOnMainThread));

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("ProCosmetics economy is now backed by ExcellentEconomy currency: " + currencyId);
    }

    /**
     * ProCosmetics raises all three purchase events on its sync executor and only after the
     * withdrawal has reported success, so the message needs no thread hop of its own.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCosmeticPurchase(PlayerPurchaseCosmeticEvent event) {
        announcePurchase(event.getUser(), event.getCosmeticType().getCost());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTreasureChestPurchase(PlayerPurchaseTreasureChestEvent event) {
        // Matches what the menu charged: the chest price times the number of chests.
        announcePurchase(event.getUser(), event.getTreasureChest().getCost() * event.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGadgetAmmoPurchase(PlayerPurchaseGadgetAmmoEvent event) {
        announcePurchase(event.getUser(), event.getGadgetType().getAmmoCost());
    }

    private void announcePurchase(User user, int amount) {
        Component rendered =
                ExcellentCurrencyEconomyProvider.render(purchaseSuccessMessage, amount, currencyName);
        // User#sendMessage resolves the player by UUID and does not guard against a null one.
        if (rendered != null && user.getPlayer() != null) {
            user.sendMessage(rendered);
        }
    }

    /**
     * Runs {@code action} on the main thread, straight away when already there.
     *
     * <p>A task is dropped once this plugin is disabled: scheduling then throws
     * {@code IllegalPluginAccessException}, and an operation completing during shutdown has
     * nobody left to message anyway.
     */
    private void runOnMainThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (isEnabled()) {
            getServer().getScheduler().runTask(this, action);
        }
    }

    /**
     * ExcellentEconomy registers its API with the Bukkit service manager while it enables, and
     * that is the contract its own documentation points integrations at. Reading it from there
     * rather than casting the plugin to {@code EconomyPlugin} keeps this off ExcellentEconomy's
     * main class, which is free to change without the API changing.
     */
    private ExcellentEconomyAPI getExcellentEconomyApi() {
        RegisteredServiceProvider<ExcellentEconomyAPI> registration =
                getServer().getServicesManager().getRegistration(ExcellentEconomyAPI.class);
        if (registration == null) {
            throw new IllegalStateException(
                    "ExcellentEconomy has not registered its API with the Bukkit service manager.");
        }
        return registration.getProvider();
    }
}
