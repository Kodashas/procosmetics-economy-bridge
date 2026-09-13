package io.github.kodashas.cosmeticsbridge;

import it.unimi.dsi.fastutil.booleans.BooleanIntPair;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import se.filledev.procosmetics.api.user.User;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.currency.operation.OperationResult;

/**
 * Self-check for the offline balance paths, run with assertions enabled by
 * {@code scripts/check.sh}.
 *
 * <p>Everything ExcellentEconomy, ProCosmetics and Bukkit provide here is a plain interface, so a
 * JDK proxy is enough to stand in for all of it and no server, no mocking library and no test
 * framework are needed. The online paths are not covered: they need a live {@code Player}.
 *
 * <p>What is worth checking here is the money: that a withdrawal larger than the balance is
 * refused rather than clamped, that a storage failure answers "no" instead of escaping as an
 * exceptional future, and that a balance that could not be read is never mistaken for zero
 * coins the player actually has.
 */
final class EconomyCheck {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    public static void main(String[] args) {
        refusesAWithdrawalLargerThanTheBalance();
        withdrawsWhenTheBalanceCoversIt();
        withdrawalFailsWhenStorageFails();
        balanceLookupFailsAsUnknownRatherThanZero();
        balanceLookupReportsTheBalance();

        System.out.println("EconomyCheck: ok");
    }

    private static void refusesAWithdrawalLargerThanTheBalance() {
        Economy economy = new Economy(100);
        boolean paid = provider(economy).removeCoinsAsync(user(), 500).join();

        assert !paid : "a withdrawal of 500 against a balance of 100 must fail";
        assert economy.withdrawals.isEmpty()
                : "nothing may be withdrawn once the balance is known to be short, got "
                        + economy.withdrawals;
    }

    private static void withdrawsWhenTheBalanceCoversIt() {
        Economy economy = new Economy(100);
        boolean paid = provider(economy).removeCoinsAsync(user(), 100).join();

        assert paid : "a withdrawal of 100 against a balance of 100 must succeed";
        assert economy.withdrawals.equals(List.of(100.0))
                : "expected one withdrawal of 100, got " + economy.withdrawals;
    }

    private static void withdrawalFailsWhenStorageFails() {
        Economy economy = new Economy(100);
        economy.failing = true;

        boolean paid = provider(economy).removeCoinsAsync(user(), 10).join();

        assert !paid : "a storage failure must answer false, not grant the purchase";
    }

    private static void balanceLookupFailsAsUnknownRatherThanZero() {
        Economy economy = new Economy(100);
        economy.failing = true;

        BooleanIntPair balance = provider(economy).getCoinsAsync(user()).join();

        // ProCosmetics reads the left boolean as "was this answered"; a false there fails
        // hasCoinsAsync outright instead of comparing a made-up balance against the price.
        assert !balance.leftBoolean() : "an unreadable balance must not be reported as an answer";
        assert balance.rightInt() == 0 : "expected 0 coins alongside the failure";
    }

    private static void balanceLookupReportsTheBalance() {
        BooleanIntPair balance = provider(new Economy(250)).getCoinsAsync(user()).join();

        assert balance.leftBoolean() : "a balance that was read must be reported as an answer";
        assert balance.rightInt() == 250 : "expected 250 coins, got " + balance.rightInt();
    }

    /** Nobody is online, so the provider takes its offline, UUID-keyed paths. */
    private static ExcellentCurrencyEconomyProvider provider(Economy economy) {
        return new ExcellentCurrencyEconomyProvider(
                economy.api(), currency(), "coins", false, quietLogger(), uuid -> null, Runnable::run);
    }

    /** ExcellentEconomy stand-in: holds one balance and records what was withdrawn. */
    private static final class Economy {

        private final double balance;
        private final List<Double> withdrawals = new ArrayList<>();
        private boolean failing;

        private Economy(double balance) {
            this.balance = balance;
        }

        private ExcellentEconomyAPI api() {
            return proxy(ExcellentEconomyAPI.class, (proxy, method, args) -> switch (method.getName()) {
                case "getBalanceAsync" -> failing
                        ? CompletableFuture.failedFuture(new IllegalStateException("storage is down"))
                        : CompletableFuture.completedFuture(balance);
                case "withdrawAsync" -> {
                    withdrawals.add((Double) args[2]);
                    yield CompletableFuture.completedFuture(OperationResult.SUCCESS);
                }
                default -> throw new AssertionError("unexpected economy call: " + method.getName());
            });
        }
    }

    private static User user() {
        return proxy(User.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> PLAYER;
            case "getPlayer" -> null;
            default -> throw new AssertionError("unexpected user call: " + method.getName());
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
