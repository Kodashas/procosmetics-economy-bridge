package io.github.kodashas.cosmeticsbridge;

import it.unimi.dsi.fastutil.booleans.BooleanIntPair;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
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

    private final ExcellentEconomyAPI excellentEconomy;
    private final String currencyId;
    private final String currencyName;
    private final String purchaseSuccessMessage;
    private final String withdrawFailureMessage;
    private final boolean debug;
    private final JavaPlugin plugin;

    private ExcellentCurrency currency;

    ExcellentCurrencyEconomyProvider(
            ExcellentEconomyAPI excellentEconomy,
            String currencyId,
            String currencyName,
            String purchaseSuccessMessage,
            String withdrawFailureMessage,
            boolean debug,
            JavaPlugin plugin) {
        this.excellentEconomy = excellentEconomy;
        this.currencyId = currencyId;
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

    /** Resolves the configured currency. Called once at enable, before registration. */
    public void hook(ProCosmetics proCosmetics) throws IllegalStateException {
        this.currency = excellentEconomy.currencyById(currencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "ExcellentEconomy has no currency with id '" + currencyId + "'."));
    }

    @Override
    public int getCoins(User user) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            return (int) excellentEconomy.getBalance(player, currency);
        }
        return excellentEconomy.getCachedUserData(user.getUniqueId())
                .map(data -> (int) data.getBalance(currency))
                .orElse(0);
    }

    @Override
    public CompletableFuture<BooleanIntPair> getCoinsAsync(User user) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            return CompletableFuture.completedFuture(
                    BooleanIntPair.of(true, (int) excellentEconomy.getBalance(player, currency)));
        }
        return excellentEconomy.getBalanceAsync(user.getUniqueId(), currency)
                .thenApply(balance -> BooleanIntPair.of(true, (int) (double) balance));
    }

    @Override
    public CompletableFuture<Boolean> addCoinsAsync(User user, int amount) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            boolean success = excellentEconomy.deposit(player, currency, amount);
            logDebug(user, amount, "deposit", success ? "SUCCESS" : "FAILURE");
            return CompletableFuture.completedFuture(success);
        }
        return excellentEconomy.depositAsync(user.getUniqueId(), currency, amount)
                .thenApply(result -> {
                    boolean success = result == OperationResult.SUCCESS;
                    logDebug(user, amount, "deposit", success ? "SUCCESS" : "FAILURE");
                    return success;
                });
    }

    @Override
    public CompletableFuture<Boolean> setCoinsAsync(User user, int amount) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            boolean success = excellentEconomy.setBalance(player, currency, amount);
            logDebug(user, amount, "set-balance", success ? "SUCCESS" : "FAILURE");
            return CompletableFuture.completedFuture(success);
        }
        return excellentEconomy.setBalanceAsync(user.getUniqueId(), currency, amount)
                .thenApply(result -> {
                    boolean success = result == OperationResult.SUCCESS;
                    logDebug(user, amount, "set-balance", success ? "SUCCESS" : "FAILURE");
                    return success;
                });
    }

    @Override
    public CompletableFuture<Boolean> removeCoinsAsync(User user, int amount) {
        Player player = Bukkit.getPlayer(user.getUniqueId());
        if (player != null) {
            boolean success = excellentEconomy.withdraw(player, currency, amount);
            if (success) {
                sendPurchaseSuccessMessage(user, amount);
                logDebug(user, amount, "withdraw", "SUCCESS");
            } else {
                sendWithdrawFailureMessage(user, amount);
                logWithdrawFailure(user, amount, "balance=" + getCoins(user));
                logDebug(user, amount, "withdraw", "FAILURE");
            }
            return CompletableFuture.completedFuture(success);
        }
        return excellentEconomy.withdrawAsync(user.getUniqueId(), currency, amount)
                .thenCompose(result -> completeWithdraw(user, amount, result));
    }

    private CompletionStage<Boolean> completeWithdraw(User user, int amount, OperationResult result) {
        boolean success = result == OperationResult.SUCCESS;
        if (success) {
            sendPurchaseSuccessMessage(user, amount);
            logDebug(user, amount, "withdraw", "SUCCESS");
        } else {
            sendWithdrawFailureMessage(user, amount);
            logWithdrawFailure(user, amount, String.valueOf(result));
            logDebug(user, amount, "withdraw", "FAILURE");
        }
        return CompletableFuture.completedFuture(success);
    }

    @Override
    public void sendInsufficientCoinsMessage(User user, int amount) {
        int missing = Math.max(amount - getCoins(user), 0);
        runOnMainThread(() -> user.sendMessage(user.translate(
                "player.not_enough_coins",
                Placeholder.unparsed("amount", String.valueOf(missing)),
                Placeholder.unparsed("currency", currencyName))));
    }

    private void sendPurchaseSuccessMessage(User user, int amount) {
        sendConfiguredMessage(user, purchaseSuccessMessage, amount);
    }

    private void sendWithdrawFailureMessage(User user, int amount) {
        sendConfiguredMessage(user, withdrawFailureMessage, amount);
    }

    private void sendConfiguredMessage(User user, String message, int amount) {
        if (message == null || message.isEmpty()) {
            return;
        }
        runOnMainThread(() -> user.sendMessage(MINI_MESSAGE.deserialize(
                message,
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("currency", currencyName))));
    }

    private void runOnMainThread(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private void logDebug(User user, int amount, String action, String outcome) {
        if (!debug) {
            return;
        }
        plugin.getLogger().log(
                Level.INFO,
                "[debug] action={0}, currency={1}, user={2}, amount={3}, outcome={4}",
                new Object[] {action, currencyId, user.getUniqueId(), amount, outcome});
    }

    private void logWithdrawFailure(User user, int amount, String reason) {
        plugin.getLogger().log(
                Level.WARNING,
                "Failed to withdraw {0} {1} for ProCosmetics purchase of user {2}: {3}",
                new Object[] {amount, currencyId, user.getUniqueId(), reason});
    }
}
