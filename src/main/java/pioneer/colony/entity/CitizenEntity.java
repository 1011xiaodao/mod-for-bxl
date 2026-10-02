package pioneer.colony.entity;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import pioneer.colony.raid.RaidManager;

/**
 * 市民参战实体（M6.4）：袭击期间由人口数据临时生成的真实体——守卫战力 100%、
 * 其他职业 25%（受研究加成）；伤亡由真实战斗结算并写回人口（06 §6 定稿"该死几个死几个"）。
 * M6.5 将演化为常驻市民模拟实体（日程通勤），本类先行承载战斗语义。
 * 实体带独立标记（pioneer_colony_citizen），随战斗结束清除；无活跃袭击时自动自清（防孤儿）。
 */
public class CitizenEntity extends PathfinderMob {
    private UUID colonyUuid;
    private boolean guard;
    private double baseDamage = 1.0;

    public CitizenEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.ARMOR, 4.0);
    }

    /** 袭击开始时由 RaidManager 配置（战后清除，不持久化为常驻市民）。 */
    public void setup(UUID colonyUuid, boolean guard, String name, double attackDamage) {
        this.colonyUuid = colonyUuid;
        this.guard = guard;
        this.baseDamage = attackDamage;
        this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(attackDamage);
        this.setCustomName(Component.literal(name));
        this.setCustomNameVisible(true);
        this.addTag("pioneer_colony_citizen");
        // 孤儿清理依据（defense in depth：save 路径也写）
        this.getPersistentData().putUUID("ColonyUUID", colonyUuid);
    }

    public UUID colonyUuid() {
        return colonyUuid;
    }

    public boolean isGuard() {
        return guard;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1, false));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true));
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hurt = super.doHurtTarget(target);
        if (hurt && target instanceof LivingEntity living) {
            living.hurt(this.damageSources().mobAttack(this), (float) Math.max(0.5, baseDamage));
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) {
            RaidManager.onCitizenDeath(this);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (colonyUuid != null) {
            tag.putUUID("ColonyUUID", colonyUuid);
        }
        tag.putBoolean("IsGuard", guard);
        tag.putDouble("BaseDamage", baseDamage);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        colonyUuid = tag.hasUUID("ColonyUUID") ? tag.getUUID("ColonyUUID") : null;
        guard = tag.getBoolean("IsGuard");
        baseDamage = tag.getDouble("BaseDamage");
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false; // 由 RaidManager 统一清除
    }
}
