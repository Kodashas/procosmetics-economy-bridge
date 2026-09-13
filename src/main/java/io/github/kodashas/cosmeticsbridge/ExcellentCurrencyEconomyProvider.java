package io.github.kodashas.cosmeticsbridge;

import it.unimi.dsi.fastutil.booleans.BooleanIntPair;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import se.filledev.procosmetics.api.ProCosmetics;
import se.filledev.procosmetics.api.economy.EconomyProvider;
import se.filledev.procosmetics.api.user.User;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;

/**
 * ProCosmetics {@link EconomyProvider} backed by one ExcellentEconomy currency.
 *
 * <p>Every operation has a synchronous path for online players and an asynchronous path
 * keyed by UUID for offline ones, mirroring what ExcellentEconomy itself offers.
 * ProCosmetics may call these off the main thread, so anything that touches a player is
 * pushed back onto the main thread.
 */
final class ExcellentCurrencyEconomyProvider implements EconomyProvider {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ExcellentEconomyAPI economy;
    private final ExcellentCurrency currency;
    private final String currencyId;
    private final String currencyName;
    private final String purchaseSuccessMessage;
    private final String withdrawFailureMessage;
    private final boolean debug;
    private final JavaPlugin plugin;

    ExcellentCurrencyEconomyProvider(
            ExcellentEconomyAPI economy,
            ExcellentCurrency currency,
            String currencyName,
            String purchaseSuccessMessage,
            String withdrawFailureMessage,
            boolean debug,
            JavaPlugin plugin) {
        this.economy = economy;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.currencyId = currency.getId();
        this.currencyName = currencyName;
        this.purchaseSuccessMessage = purchaseSuccessMessage;
        this.withdrawFailureMessage = withdrawFailureMessage;
        this.debug = debug;
        this.plugin = plugin;
    }

    @Override
    public String getPlugin() {
        return "ExcellentEconomy (" + currencyId + ")";
    }

    /**
     * Never called for a provider registered through {@code EconomyManager#register}: that
     * method only stores the provider, and ProCosmetics' single {@code hook()} call site runs
     * during its own enable, before this plugin loads. The currency is therefore resolved in
     * {@link EconomyBridgePlugin#onEnable()} instead. The method stays because the interface
     * declares it.
     */
    @Override
    public void hook(ProCosmetics proCosmetics) {
        // Nothing to do.
    }

    /**
     * {@inheritDoc}
     *
     * <p>For an offline player this can only answer from ExcellentEconomy's cache. When the
     * cache has nothing, there is no synchronous way to find out and the contract forces a
     * number, so this returns 0 — which ProCosmetics reads as "cannot afford it". The miss is
     * logged, because a silent 0 looks exactly like a real empty balance.
     */
    @Override
    public int getCoins(User user) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            return (int) economy.getBalance(player, currency);
        }
        return economy.getCachedUserData(user.getUniqueId())
                .map(data -> (int) data.getBalance(currency))
                .orElseGet(() -> {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "No cached {0} balance for offline user {1}; reporting 0 to ProCosmetics",
                            new Object[] {currencyId, user.getUniqueId()});
                    return 0;
                });
    }

    @Override
    public CompletableFuture<BooleanIntPair> getCoinsAsync(User user) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            return CompletableFuture.completedFuture(
                    BooleanIntPair.of(true, (int) economy.getBalance(player, currency)));
        }
        return economy.getBalanceAsync(user.getUniqueId(), currency)
                .thenApply(balance -> BooleanIntPair.of(true, (int) (double) balance));
    }

    @Override
    public CompletableFuture<Boolean> addCoinsAsync(User user, int amount) {
        return apply(user, amount, "deposit",
                player -> economy.deposit(player, currency, amount),
                uuid -> economy.depositAsync(uuid, currency, amount));
    }

    @Override
    public CompletableFuture<Boolean> setCoinsAsync(User user, int amount) {
        return apply(user, amount, "set-balance",
                player -> economy.setBalance(player, currency, amount),
                uuid -> economy.setBalanceAsync(uuid, currency, amount));
    }

    @Override
    public CompletableFuture<Boolean> removeCoinsAsync(User user, int amount) {
        return apply(user, amount, "withdraw",
                player -> economy.withdraw(player, currency, amount),
                uuid -> economy.withdrawAsync(uuid, currency, amount))
                .thenApply(success -> {
                    announceWithdraw(user, amount, success);
                    return success;
                });
    }

    /**
     * Runs {@code online} for a player who is on the server and {@code offline} for one who is
     * not, normalising both results to a boolean and logging the outcome when debug is on.
     */
    private CompletableFuture<Boolean> apply(
            User user,
            int amount,
            String action,
            Predicate<Player> online,
            Function<UUID, CompletableFuture<OperationResult>> offline) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            return CompletableFuture.completedFuture(logged(user, amount, action, online.test(player)));
        }
        return offline.apply(user.getUniqueId())
                .thenApply(result -> logged(user, amount, action, result == OperationResult.SUCCESS));
    }

    private boolean logged(User user, int amount, String action, boolean success) {
        if (debug) {
            plugin.getLogger().log(
                    Level.INFO,
                    "[debug] action={0}, currency={1}, user={2}, amount={3}, outcome={4}",
                    new Object[] {action, currencyId, user.getUniqueId(), amount, success ? "SUCCESS" : "FAILURE"});
        }
        return success;
    }

    private void announceWithdraw(User user, int amount, boolean success) {
        sendMessage(user, success ? purchaseSuccessMessage : withdrawFailureMessage, amount);
        if (!success) {
            // No balance lookup here on purpose: this runs on whichever thread completed the
            // withdrawal, and reading the balance again would touch the server off-thread.
            plugin.getLogger().log(
                    Level.WARNING,
                    "Failed to withdraw {0} {1} for a ProCosmetics purchase by {2}",
                    new Object[] {amount, currencyId, user.getUniqueId()});
        }
    }

    @Override
    public void sendInsufficientCoinsMessage(User user, int amount) {
        // getCoins() reads the balance and may look the player up, so it runs on the main
        // thread together with the message rather than on whatever thread called us.
        runOnMainThread(() -> {
            int missing = Math.max(amount - getCoins(user), 0);
            user.sendMessage(user.translate(
                    "player.not_enough_coins",
                    Placeholder.unparsed("amount", String.valueOf(missing)),
                    Placeholder.unparsed("currency", currencyName)));
        });
    }

    private void sendMessage(User user, String message, int amount) {
        Component rendered = render(message, amount, currencyName);
        if (rendered != null) {
            runOnMainThread(() -> user.sendMessage(rendered));
        }
    }

    /**
     * Renders a configured MiniMessage template, or returns {@code null} when the template is
     * blank — an operator empties a message to turn it off.
     *
     * <p>Package-private and free of Bukkit so it can be exercised on its own; see
     * {@code RenderCheck} in the test sources.
     */
    static Component render(String message, int amount, String currencyName) {
        if (message == null || message.isEmpty()) {
            return null;
        }
        return MINI_MESSAGE.deserialize(
                message,
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("currency", currencyName));
    }

    private void runOnMainThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, action);
        }
        // Dropped when the plugin is already disabled: scheduling then throws
        // IllegalPluginAccessException, and an async operation completing during shutdown
        // has nobody left to message anyway.
    }
}
