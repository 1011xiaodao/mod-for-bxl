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
pub struct  Id(u16);
#[derive(Component)]
pub struct Population(u32);
#[derive(Component)]
pub struct Funds(u32);
#[derive(Component)]
pub struct HappinessLevel(f32);
#[derive(Component)]
pub struct Food(u32);
#[derive(Component)]
pub struct Works(u32);

pub struct EconomyDelta {

    pub entity: Entity,

    pub population: i32,

    pub food: i32,

    pub funds: i32,

    pub happiness: f32,

}