package me.justeli.coins.item;

import me.justeli.coins.Coins;
import me.justeli.coins.config.Config;
import me.justeli.coins.util.Skull;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class BaseCoin {
    private final MetaBuilder withdrawnCoin;
    private final MetaBuilder droppedCoin;
    private final MetaBuilder otherCoin;

    // Default: use Config values
    public BaseCoin(Coins coins) {
        this(coins, Config.SKULL_TEXTURE, Config.CUSTOM_MODEL_DATA);
    }

    // Override only skull texture (keep Config model data)
    public BaseCoin(Coins coins, String skullTexture) {
        this(coins, skullTexture, Config.CUSTOM_MODEL_DATA);
    }

    // Override only model data (keep Config skull texture)
    public BaseCoin(Coins coins, int customModelData) {
        this(coins, Config.SKULL_TEXTURE, customModelData);
    }

    // Master constructor does all the work
    public BaseCoin(Coins coins, String skullTexture, int customModelData) {
        ItemStack baseCoin = (skullTexture == null || skullTexture.isEmpty())
                ? new ItemStack(Config.COIN_ITEM)
                : Skull.of(skullTexture);

        ItemMeta baseCoinMeta = baseCoin.getItemMeta();
        if (baseCoinMeta != null) {
            if (customModelData > 0) {
                baseCoinMeta.setCustomModelData(customModelData);
            }
            if (Config.ENCHANTED_COIN) {
                baseCoinMeta.addEnchant(Enchantment.DURABILITY, 1, true);
                baseCoinMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            baseCoin.setItemMeta(baseCoinMeta);
        }

        this.withdrawnCoin = coins.meta(baseCoin.clone())
                .data(CoinUtil.COINS_TYPE, CoinUtil.TYPE_WITHDRAWN);

        MetaBuilder droppedCoinItem = coins.meta(baseCoin.clone())
                .name(Config.DROPPED_COIN_NAME)
                .data(CoinUtil.COINS_TYPE, CoinUtil.TYPE_DROPPED);

        if (Config.DROP_EACH_COIN) {
            droppedCoinItem.data(CoinUtil.COINS_WORTH, 1D);
        }

        this.droppedCoin = droppedCoinItem;
        this.otherCoin = coins.meta(baseCoin.clone())
                .name(Config.DROPPED_COIN_NAME)
                .data(CoinUtil.COINS_TYPE, CoinUtil.TYPE_OTHER);
    }
    public void setTexture(Integer texture) {

    }
    public MetaBuilder dropped()   { return this.droppedCoin.clone(); }
    public MetaBuilder withdrawn() { return this.withdrawnCoin.clone(); }
    public MetaBuilder other()     { return this.otherCoin.clone(); }
}
