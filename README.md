# ProCosmetics Economy Bridge

Makes [ProCosmetics](https://www.spigotmc.org/resources/procosmetics-350-cosmetics-treasure-chests.137754/) charge cosmetic
purchases against an **ExcellentEconomy** currency instead of its own internal coin balance.

If your server already runs ExcellentEconomy and you want cosmetics paid for with a currency
players actually earn — coins, tokens, gems, whatever you have configured — this is the piece
in between.

ProCosmetics can already talk to Vault, but Vault exposes a single balance. ExcellentEconomy
has named currencies, and this plugin points cosmetics at exactly one of them.

## What it does

ProCosmetics accepts exactly one `EconomyProvider`. This plugin registers its own at enable
time, replacing the built-in one. Nothing else about ProCosmetics changes: menus, cosmetics,
permissions and prices stay as they are, they are just paid for from a different balance.

Every operation has two paths:

- player online → synchronous ExcellentEconomy call
- player offline → the async, UUID-keyed call

ProCosmetics may call the provider off the main thread. Balance operations run on the calling
thread, which ExcellentEconomy supports; only messages to a player are pushed back onto the
main thread.

A withdrawal checks the balance first. ExcellentEconomy clamps a balance at zero and still
reports success, so without that check taking more than a player owns would read as a paid
purchase. The check and the withdrawal are two operations rather than one atomic one, so a
balance change landing between them can still slip through.

The purchase message is sent from listeners on ProCosmetics' three purchase events, not from
the provider: `removeCoinsAsync` is a generic balance operation that an admin
`/procosmetics remove coins` also goes through, and announcing a payment there told players
they had bought something when nothing had been bought.

## Requirements

- Paper or a Paper fork, Minecraft 26.1+ (ProCosmetics 2.0.7 requirement; built against `paper-api` 26.2)
- Java 25+ on the server — required by ProCosmetics 2.0.7 itself. This plugin targets release 21, so it runs on anything from 21 upwards.
- [ProCosmetics](https://www.spigotmc.org/resources/procosmetics-350-cosmetics-treasure-chests.137754/)
- [ExcellentEconomy](https://modrinth.com/plugin/excellenteconomy), and nightcore which it depends on

Both plugins are hard dependencies in `plugin.yml`, so the server refuses to enable this
plugin without them.

## Configuration

`plugins/ProCosmeticsEconomyBridge/config.yml`:

```yaml
currency-id: coins
currency-name: coins
purchase-success-message: <green>Paid <yellow><amount> <currency></yellow><green>.</green>
debug: false
```

| Key | Meaning |
|---|---|
| `currency-id` | Currency id as configured in ExcellentEconomy |
| `currency-name` | How the currency is written in messages, in whatever grammatical form your language needs |
| `purchase-success-message` | MiniMessage, sent after a successful purchase |
| `debug` | Logs every balance operation at INFO |

The message supports `<amount>` and `<currency>`.

Nothing is sent when a withdrawal fails: ProCosmetics already messages the player from its
own purchase menu, so a second message would double up. The failure is logged at WARNING.

The "not enough coins" message is deliberately **not** configured here. It comes from
ProCosmetics' own `player.not_enough_coins` translation key, fed the same two placeholders, so
it follows your ProCosmetics language file and stays consistent with its other menus.

The currency is resolved during enable, before the provider is registered. A wrong
`currency-id` therefore fails the enable with a clear error instead of surfacing later, in
front of a player.

## Building

Every dependency resolves from a public Maven repository, so a clone builds on its own:

```bash
mvn clean package
```

`paper-api` comes from the PaperMC repository, `procosmetics-api` from `repo.filledev.se`,
`ExcellentEconomy` and nightcore from `repo.nightexpressdev.com`, and fastutil from Maven
Central. Nothing has to be copied out of a server's `plugins` folder.

ProCosmetics publishes only its API module, and that module lags the plugin: 2.0.1 is the
newest one on the repository while the plugin is at 2.0.7. Every type this bridge touches has
an identical signature in both, so it compiles against 2.0.1 — worth re-checking before moving
to a newer ProCosmetics.

Output: `target/ProCosmeticsEconomyBridge-1.0.1.jar`.

GitHub Actions runs the same build on every push and pull request. Pushing a `v*` tag makes it
build from that tag and attach the jar to the matching release, so a release asset always comes
from the commit the tag points at.

Two self-checks run without a server:

```bash
scripts/check.sh
```

`RenderCheck` covers message rendering: that both placeholders are substituted, that a blank
template sends nothing, and that a currency name containing markup is inserted as text rather
than parsed as a MiniMessage tag.

`EconomyCheck` covers the provider, which is where the money is: that a withdrawal larger than
the balance is refused instead of clamped, online and offline alike; that an online player
takes the synchronous path and an offline one the UUID-keyed path; that a storage failure
answers "no" rather than escaping as an exceptional future; that a balance which could not be
read is never mistaken for a player holding zero coins; and that nothing is sent to a player
who has already logged off. Everything it stands in for — ExcellentEconomy, ProCosmetics, the
Bukkit player — is a plain interface behind a JDK proxy, so neither a mocking library nor a
test framework is involved.

Both print `<name>: ok` and the script exits 0 when everything holds.

## Installing

Drop the jar into `plugins/` and restart. Set `currency-id` to match your ExcellentEconomy
currency, then restart once more.

## Licence

MIT. See [LICENSE](LICENSE).
