package pioneer.colony.entity;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import pioneer.colony.citizen.CitizenManager;
import pioneer.colony.raid.RaidManager;

/**
 * 市民实体。两种形态（同一实体类，M6.4 袭击临时体 → M6.5 演化）：
 * <ul>
 *   <li>临时参战体（permanent=false）：袭击期间由袭击系统生成，随战斗结束清除（M6.4 行为保留）；</li>
 *   <li>常驻市民（permanent=true）：与原版村民同机制——常驻存档于所在区块、跟随服务器模拟距离
 *       tick，由 CitizenManager 按人口数据补生成/回收并驱动日程通勤（家→工坊→食堂→家），
 *       死亡按真实战斗结果写回人口（06 §6/§8.1）。</li>
 * </ul>
 * 寻路纪律：按需一次寻路（目标变化才 moveTo），卡住由管理器计时传送兜底；
 * 实体只是表现层，生产数字永远由数据层公式决定（06 §2 数据与实体边界）。
 */
public class CitizenEntity extends PathfinderMob {
    /** 日程目标。RALLY = 袭击动员（向总部集结，战斗由 AI 自动接敌）。 */
    public enum ScheduleGoal { IDLE, HOME, WORK, CANTEEN, RALLY }

    private UUID colonyUuid;
    private boolean guard;
    private double baseDamage = 1.0;
    // —— 常驻市民（M6.5） ——
    private boolean permanent;
    private long homeOrigin;
    private boolean homeValid;
    private long workOrigin;
    private boolean workValid;
    private String workDefId = "";
    private ScheduleGoal goal = ScheduleGoal.IDLE;
    private long goalTarget;
    private boolean goalTargetValid;
    private int lastMealKey = -1;
    /** 卡住计时（秒，由 CitizenManager 每秒判定累加；寻路受阻保险丝）。 */
    private int stuckSeconds = 0;
    /** 换目标导航抖动倒计时（秒）：日程切换全员同拍时把寻路摊到数秒内（06 §9 寻路尖峰纪律）。 */
    private int navDelaySeconds = -1;
    private double anchorX, anchorY, anchorZ;

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
        this.addTag(RaidManager.TAG_CITIZEN);
        // 孤儿清理依据（defense in depth：save 路径也写）
        this.getPersistentData().putUUID("ColonyUUID", colonyUuid);
    }

    /** 常驻市民初始化（CitizenManager 调用）：名字随机池、常驻不消失、独立标记。 */
    public void setupPermanent(UUID colonyUuid, String name) {
        this.colonyUuid = colonyUuid;
        this.permanent = true;
        this.setCustomName(Component.literal(name));
        this.setCustomNameVisible(true);
        this.addTag(CitizenManager.TAG_RESIDENT);
        this.setPersistenceRequired();
        this.getPersistentData().putUUID("ColonyUUID", colonyUuid);
    }

    public UUID colonyUuid() {
        return colonyUuid;
    }

    public boolean isGuard() {
        return guard;
    }

    public boolean isPermanent() {
        return permanent;
    }

    /** 应用岗位战力（守卫 100%/其余 25%，受研究加成；岗位变动时重算）。 */
    public void applyRole(boolean guardRole, double attackDamage) {
        this.guard = guardRole;
        this.baseDamage = attackDamage;
        this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(attackDamage);
        if (guardRole && this.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
            this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                            net.minecraft.resources.ResourceLocation.withDefaultNamespace("iron_sword"))));
        } else if (!guardRole) {
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    // —— 住/岗指派（CitizenManager 维护；origin 为 BlockPos.asLong，有效性用显式布尔而非 <0 哨兵） ——

    public long homeOrigin() {
        return homeOrigin;
    }

    public boolean hasHome() {
        return homeValid;
    }

    public void setHome(long origin, boolean valid) {
        this.homeOrigin = origin;
        this.homeValid = valid;
    }

    public long workOrigin() {
        return workOrigin;
    }

    public boolean hasWork() {
        return workValid;
    }

    public String workDefId() {
        return workDefId;
    }

    public void setWork(long origin, boolean valid, String definitionId) {
        this.workOrigin = origin;
        this.workValid = valid;
        this.workDefId = valid ? definitionId : "";
    }

    public ScheduleGoal goal() {
        return goal;
    }

    public void setGoal(ScheduleGoal goal) {
        this.goal = goal;
    }

    public int lastMealKey() {
        return lastMealKey;
    }

    public void setLastMealKey(int key) {
        this.lastMealKey = key;
    }

    /** 设定日程目标并按需一次寻路（目标不变不重寻，06 §9 寻路开销纪律）。 */
    public void navigateTo(BlockPos target) {
        this.goalTarget = target.asLong();
        this.goalTargetValid = true;
        this.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        this.stuckSeconds = 0;
        this.recordAnchor();
    }

    public void stopMoving() {
        this.goalTargetValid = false;
        this.getNavigation().stop();
    }

    public boolean hasGoalTarget() {
        return goalTargetValid;
    }

    public BlockPos goalTargetPos() {
        return BlockPos.of(goalTarget);
    }

    /** 到达判定：寻路已结束且距目标 3 格内。 */
    public boolean atGoalTarget() {
        if (!goalTargetValid || this.getNavigation().isInProgress()) {
            return false;
        }
        BlockPos t = BlockPos.of(goalTarget);
        return this.distanceToSqr(t.getX() + 0.5, t.getY(), t.getZ() + 0.5) < 9.0;
    }

    public void recordAnchor() {
        this.anchorX = getX();
        this.anchorY = getY();
        this.anchorZ = getZ();
    }

    /** 自锚点漂移量（管理器每秒比对，判定卡住）。 */
    public double anchorDrift() {
        double dx = getX() - anchorX, dy = getY() - anchorY, dz = getZ() - anchorZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public int bumpStuck() {
        return ++stuckSeconds;
    }

    public int stuckSeconds() {
        return stuckSeconds;
    }

    public void resetStuck() {
        stuckSeconds = 0;
    }

    public void setNavDelay(int seconds) {
        this.navDelaySeconds = seconds;
    }

    public int navDelay() {
        return navDelaySeconds;
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
            if (permanent) {
                CitizenManager.onCitizenDeath(this, source);
            } else {
                RaidManager.onCitizenDeath(this);
            }
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
        tag.putBoolean("Permanent", permanent);
        if (permanent) {
            tag.putLong("HomeOrigin", homeOrigin);
            tag.putBoolean("HomeValid", homeValid);
            tag.putLong("WorkOrigin", workOrigin);
            tag.putBoolean("WorkValid", workValid);
            tag.putString("WorkDefId", workDefId);
            tag.putString("ScheduleGoal", goal.name());
            tag.putInt("LastMealKey", lastMealKey);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        colonyUuid = tag.hasUUID("ColonyUUID") ? tag.getUUID("ColonyUUID") : null;
        guard = tag.getBoolean("IsGuard");
        baseDamage = tag.getDouble("BaseDamage");
        permanent = tag.getBoolean("Permanent");
        if (permanent) {
            homeOrigin = tag.getLong("HomeOrigin");
            homeValid = tag.getBoolean("HomeValid");
            workOrigin = tag.getLong("WorkOrigin");
            workValid = tag.getBoolean("WorkValid");
            workDefId = tag.getString("WorkDefId");
            try {
                goal = ScheduleGoal.valueOf(tag.getString("ScheduleGoal"));
            } catch (IllegalArgumentException e) {
                goal = ScheduleGoal.IDLE;
            }
            lastMealKey = tag.getInt("LastMealKey");
            goalTargetValid = false; // 重载后由管理器按日程重新导航
        }
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false; // 常驻市民随区块存档；临时体由 RaidManager 统一清除
    }
}
