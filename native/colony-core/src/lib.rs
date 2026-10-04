use bevy_ecs::prelude::*;

pub mod economy;

pub mod citizen;

pub mod market;

pub mod gauge;

pub mod happiness;

pub mod trade;
mod err;

fn main () {
    let gold = 0;

}
#[derive(Resource)]
struct Time {
    tick:i64
}
#[derive(Component)]
struct Id(u16);
#[derive(Component)]
struct Population(u32);
#[derive(Component)]
struct Funds(u32);
#[derive(Component)]
struct HappinessLevel(f32);
#[derive(Component)]
struct Food(u32);
#[derive(Component)]
struct Works(u32);

struct PopulationsDelta {
    entity: Entity,
    delta: i32,
}
struct FundsDelta {
    entity: Entity,
    delta: i32,
}
struct HappinessLevelDelta {
    entity: Entity,
    level: i32,
}
struct FoodDelta {
    entity: Entity,
    delta: i32,
}