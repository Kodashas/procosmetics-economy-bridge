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
 *
 * <p>ProCosmetics may call these off the main thread. The balance operations run on the
 * calling thread, which ExcellentEconomy supports — balances live in concurrent structures
 * and its own {@code ChangeBalanceEvent} marks itself asynchronous when it is raised off the
 * primary thread — but anything that messages a player is pushed onto the main thread first.
 */
final class ExcellentCurrencyEconomyProvider implements EconomyProvider {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ExcellentEconomyAPI economy;
    private final ExcellentCurrency currency;
    private final String currencyId;
    private final String currencyName;
    private final boolean debug;
    private final JavaPlugin plugin;

    ExcellentCurrencyEconomyProvider(
            ExcellentEconomyAPI economy,
            ExcellentCurrency currency,
            String currencyName,
            boolean debug,
            JavaPlugin plugin) {
        this.economy = economy;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.currencyId = currency.getId();
        this.currencyName = currencyName;
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

    /**
     * {@inheritDoc}
     *
     * <p>The balance is checked here rather than left to ExcellentEconomy: its withdrawal
     * clamps the balance at zero and still reports {@code SUCCESS}, so taking more than a
     * player owns would look like a paid purchase. ProCosmetics' own Vault provider refuses
     * an over-withdrawal the same way.
     *
     * <p>Nothing is sent to the player from here. ProCosmetics' purchase menus call
     * {@link #sendInsufficientCoinsMessage} from their own completion handler, and an admin
     * {@code /procosmetics remove coins} ends up in this same method.
     */
    @Override
    public CompletableFuture<Boolean> removeCoinsAsync(User user, int amount) {
        // ponytail: check-then-withdraw, not one atomic operation. A balance change that lands
        // between the two still slips through; closing that needs a withdrawIfEnough() on the
        // ExcellentEconomy side.
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            boolean enough = economy.getBalance(player, currency) >= amount;
            return CompletableFuture.completedFuture(withdrawn(
                    user, amount, enough && economy.withdraw(player, currency, amount)));
        }
        UUID uuid = user.getUniqueId();
        return economy.getBalanceAsync(uuid, currency)
                .thenCompose(balance -> balance >= amount
                        ? economy.withdrawAsync(uuid, currency, amount)
                        : CompletableFuture.completedFuture(OperationResult.FAILURE))
                .thenApply(result -> withdrawn(user, amount, result == OperationResult.SUCCESS));
    }

    private boolean withdrawn(User user, int amount, boolean success) {
        if (!success) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Failed to withdraw {0} {1} from {2}",
                    new Object[] {amount, currencyId, user.getUniqueId()});
        }
        return logged(user, amount, "withdraw", success);
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

    @Override
    public void sendInsufficientCoinsMessage(User user, int amount) {
        // getCoins() reads the balance and may look the player up, so it runs on the main
        // thread together with the message rather than on whatever thread called us.
        onMainThreadIfOnline(user, () -> user.sendMessage(user.translate(
                "player.not_enough_coins",
                Placeholder.unparsed("amount", String.valueOf(Math.max(amount - getCoins(user), 0))),
                Placeholder.unparsed("currency", currencyName))));
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

    /**
     * Runs {@code action} on the main thread, but only while the player is still online.
     *
     * <p>ProCosmetics resolves the player by UUID inside {@code User#sendMessage} and does not
     * guard against a null one, so messaging a player who logged off between the operation
     * completing and this task running throws.
     */
    private void onMainThreadIfOnline(User user, Runnable action) {
        runOnMainThread(() -> {
            if (user.getPlayer() != null) {
                action.run();
            }
        });
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
