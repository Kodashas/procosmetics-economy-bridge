package io.github.kodashas.cosmeticsbridge;

import it.unimi.dsi.fastutil.booleans.BooleanIntPair;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import se.filledev.procosmetics.api.user.User;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;

/**
 * Self-check for the provider, run with assertions enabled by {@code scripts/check.sh}.
 *
 * <p>Everything the provider talks to — ExcellentEconomy, ProCosmetics, the Bukkit player — is
 * a plain interface, so a JDK proxy stands in for all of it and no server, mocking library or
 * test framework is involved. The player lookup and the main-thread hop are constructor
 * arguments for the same reason.
 *
 * <p>What is worth checking is the money and the two things that go wrong around it: a
 * withdrawal larger than the balance must be refused rather than clamped, a storage failure
 * must answer "no" instead of escaping as an exceptional future, a balance that could not be
 * read must never pass for a player holding zero coins, and nothing may be sent to a player
 * who has already logged off.
 */
final class EconomyCheck {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    public static void main(String[] args) {
        refusesAWithdrawalLargerThanTheBalance();
        withdrawsWhenTheBalanceCoversIt();
        withdrawalFailsWhenStorageFails();
        balanceLookupFailsAsUnknownRatherThanZero();
        balanceLookupReportsTheBalance();

        chargesAnOnlinePlayerThroughTheSynchronousPath();
        refusesAnOnlinePlayerWhoCannotAffordIt();

        depositReportsWhatStorageAnswered();
        depositFailureIsCaught();

        uncachedOfflineBalanceReadsAsZero();

        saysNothingToAPlayerWhoLoggedOff();
        tellsAPlayerStillOnlineWhatIsMissing();

        System.out.println("EconomyCheck: ok");
    }

    // --- offline withdrawals -------------------------------------------------------------

    private static void refusesAWithdrawalLargerThanTheBalance() {
        Economy economy = new Economy(100);
        boolean paid = provider(economy).removeCoinsAsync(offlineUser(), 500).join();

        assert !paid : "a withdrawal of 500 against a balance of 100 must fail";
        assert economy.calls.isEmpty()
                : "nothing may be withdrawn once the balance is known to be short, got "
                        + economy.calls;
    }

    private static void withdrawsWhenTheBalanceCoversIt() {
        Economy economy = new Economy(100);
        boolean paid = provider(economy).removeCoinsAsync(offlineUser(), 100).join();

        assert paid : "a withdrawal of 100 against a balance of 100 must succeed";
        assert economy.calls.equals(List.of("withdrawAsync:100.0"))
                : "expected one async withdrawal of 100, got " + economy.calls;
    }

    private static void withdrawalFailsWhenStorageFails() {
        Economy economy = new Economy(100);
        economy.failing = true;

        boolean paid = provider(economy).removeCoinsAsync(offlineUser(), 10).join();

        assert !paid : "a storage failure must answer false, not grant the purchase";
    }

    // --- balance lookups -----------------------------------------------------------------

    private static void balanceLookupFailsAsUnknownRatherThanZero() {
        Economy economy = new Economy(100);
        economy.failing = true;

        BooleanIntPair balance = provider(economy).getCoinsAsync(offlineUser()).join();

        // ProCosmetics reads the left boolean as "was this answered"; a false there fails
        // hasCoinsAsync outright instead of comparing a made-up balance against the price.
        assert !balance.leftBoolean() : "an unreadable balance must not be reported as an answer";
        assert balance.rightInt() == 0 : "expected 0 coins alongside the failure";
    }

    private static void balanceLookupReportsTheBalance() {
        BooleanIntPair balance = provider(new Economy(250)).getCoinsAsync(offlineUser()).join();

        assert balance.leftBoolean() : "a balance that was read must be reported as an answer";
        assert balance.rightInt() == 250 : "expected 250 coins, got " + balance.rightInt();
    }

    private static void uncachedOfflineBalanceReadsAsZero() {
        // The synchronous contract forces a number out of a player who is not online and not
        // cached. Zero is the safe one: ProCosmetics reads it as "cannot afford it".
        assert provider(new Economy(500)).getCoins(offlineUser()) == 0
                : "an offline player with nothing cached must report 0";
    }

    // --- online withdrawals --------------------------------------------------------------

    private static void chargesAnOnlinePlayerThroughTheSynchronousPath() {
        Economy economy = new Economy(100);
        boolean paid = provider(economy, player()).removeCoinsAsync(onlineUser(), 100).join();

        assert paid : "an online player who can afford it must be charged";
        assert economy.calls.equals(List.of("withdraw:100.0"))
                : "an online player takes the synchronous path, got " + economy.calls;
    }

    private static void refusesAnOnlinePlayerWhoCannotAffordIt() {
        Economy economy = new Economy(100);
        boolean paid = provider(economy, player()).removeCoinsAsync(onlineUser(), 500).join();

        assert !paid : "an online player short of the price must not be charged";
        assert economy.calls.isEmpty() : "nothing may be taken, got " + economy.calls;
    }

    // --- deposits ------------------------------------------------------------------------

    private static void depositReportsWhatStorageAnswered() {
        Economy granted = new Economy(0);
        assert provider(granted).addCoinsAsync(offlineUser(), 25).join()
                : "a deposit ExcellentEconomy accepted must report success";

        Economy refused = new Economy(0);
        refused.result = OperationResult.FAILURE;
        assert !provider(refused).addCoinsAsync(offlineUser(), 25).join()
                : "a deposit ExcellentEconomy refused must report failure";
    }

    private static void depositFailureIsCaught() {
        Economy economy = new Economy(0);
        economy.failing = true;

        assert !provider(economy).addCoinsAsync(offlineUser(), 25).join()
                : "a storage failure during a deposit must answer false, not escape";
    }

    // --- messaging -----------------------------------------------------------------------

    private static void saysNothingToAPlayerWhoLoggedOff() {
        AtomicInteger sent = new AtomicInteger();

        // ProCosmetics resolves the player by UUID inside User#sendMessage without a null
        // guard, so a message to someone who left throws rather than going nowhere.
        provider(new Economy(0)).sendInsufficientCoinsMessage(offlineUser(sent), 100);

        assert sent.get() == 0 : "a player who logged off must not be messaged";
    }

    private static void tellsAPlayerStillOnlineWhatIsMissing() {
        AtomicInteger sent = new AtomicInteger();

        provider(new Economy(40), player()).sendInsufficientCoinsMessage(onlineUser(sent), 100);

        assert sent.get() == 1 : "a player who is still online must be told once, got " + sent.get();
    }

    // --- stand-ins -----------------------------------------------------------------------

    /** Nobody is online, so the provider takes its offline, UUID-keyed paths. */
    private static ExcellentCurrencyEconomyProvider provider(Economy economy) {
        return provider(economy, null);
    }

    private static ExcellentCurrencyEconomyProvider provider(Economy economy, Player online) {
        return new ExcellentCurrencyEconomyProvider(
                economy.api(), currency(), "coins", false, quietLogger(),
                uuid -> online, Runnable::run);
    }

    /** ExcellentEconomy stand-in: one balance, and a record of what was asked of it. */
    private static final class Economy {

        private final double balance;
        private final List<String> calls = new ArrayList<>();
        private OperationResult result = OperationResult.SUCCESS;
        private boolean failing;

        private Economy(double balance) {
            this.balance = balance;
        }

        private ExcellentEconomyAPI api() {
            return proxy(ExcellentEconomyAPI.class, (proxy, method, args) -> switch (method.getName()) {
                case "getBalance" -> balance;
                case "getBalanceAsync" -> failing ? failed() : CompletableFuture.completedFuture(balance);
                case "getCachedUserData" -> Optional.empty();
                case "withdraw" -> {
                    calls.add("withdraw:" + args[2]);
                    yield result == OperationResult.SUCCESS;
                }
                case "withdrawAsync", "depositAsync", "setBalanceAsync" -> {
                    if (failing) {
                        yield failed();
                    }
                    calls.add(method.getName() + ":" + args[2]);
                    yield CompletableFuture.completedFuture(result);
                }
                default -> throw new AssertionError("unexpected economy call: " + method.getName());
            });
        }

        private static CompletableFuture<Object> failed() {
            return CompletableFuture.failedFuture(new IllegalStateException("storage is down"));
        }
    }

    private static User offlineUser() {
        return offlineUser(new AtomicInteger());
    }

    private static User offlineUser(AtomicInteger sent) {
        return user(null, sent);
    }

    private static User onlineUser() {
        return onlineUser(new AtomicInteger());
    }

    private static User onlineUser(AtomicInteger sent) {
        return user(player(), sent);
    }

    private static User user(Player player, AtomicInteger sent) {
        return proxy(User.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> PLAYER;
            case "getPlayer" -> player;
            case "translate" -> Component.empty();
            case "sendMessage" -> {
                sent.incrementAndGet();
                yield null;
            }
            default -> throw new AssertionError("unexpected user call: " + method.getName());
        });
    }

    /** Only ever passed back to the economy stand-in, so nothing on it needs answering. */
    private static Player player() {
        return proxy(Player.class, (proxy, method, args) -> {
            throw new AssertionError("unexpected player call: " + method.getName());
        });
    }

    private static ExcellentCurrency currency() {
        return proxy(ExcellentCurrency.class, (proxy, method, args) -> switch (method.getName()) {
            case "getId" -> "coins";
            default -> throw new AssertionError("unexpected currency call: " + method.getName());
        });
    }

    /** The provider logs a warning on every refused withdrawal; keep it out of the output. */
    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("EconomyCheck");
        logger.setUseParentHandlers(false);
        return logger;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private EconomyCheck() {
    }
}
