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

ProCosmetics may call the provider off the main thread, so anything that touches a player is
pushed back onto the main thread first.

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
withdraw-failure-message: <red>Could not take <yellow><amount> <currency></yellow><red>. Try again or contact an admin.</red>
debug: false
```

| Key | Meaning |
|---|---|
| `currency-id` | Currency id as configured in ExcellentEconomy |
| `currency-name` | How the currency is written in messages, in whatever grammatical form your language needs |
| `purchase-success-message` | MiniMessage, sent after a successful purchase |
| `withdraw-failure-message` | MiniMessage, sent when the withdrawal fails. Empty sends nothing |
| `debug` | Logs every balance operation at INFO |

Both messages support `<amount>` and `<currency>`.

The "not enough coins" message is deliberately **not** configured here. It comes from
ProCosmetics' own `player.not_enough_coins` translation key, fed the same two placeholders, so
it follows your ProCosmetics language file and stays consistent with its other menus.

The currency is resolved when ProCosmetics registers the provider, during enable. A wrong
`currency-id` therefore fails the enable with a clear error instead of failing later, in front
of a player.

## Building

All three plugins this compiles against are free to download, but none of them publishes to a
Maven repository, so they cannot be resolved automatically. Put their jars in `libs/` first.

The easiest source is a server that already runs them:

```bash
scripts/fetch-libs.sh /path/to/server/plugins
mvn clean package
```

Otherwise download them by hand and name them `libs/ProCosmetics.jar`,
`libs/ExcellentEconomy.jar` and `libs/nightcore.jar`.

`libs/` is gitignored — those are other people's plugins and are not redistributed here.
`paper-api` comes from the PaperMC repository and fastutil from Maven Central; both are
resolved by Maven.

Output: `target/ProCosmeticsEconomyBridge-1.0.0.jar`.

## Installing

Drop the jar into `plugins/` and restart. Set `currency-id` to match your ExcellentEconomy
currency, then restart once more.

## Licence

MIT. See [LICENSE](LICENSE).
