use bevy_ecs::prelude::*;

pub mod citizen;
pub mod economy;
pub mod err;
pub mod gauge;
pub mod happiness;
pub mod market;
pub mod trade;

#[derive(Resource, Debug, Clone, Copy, Default)]
pub struct Time {
    pub tick: i64,
}

#[derive(Component, Debug, Clone, Copy)]
pub struct Id(pub u16);

#[derive(Component, Debug, Clone, Copy)]
pub struct Population(pub u32);

#[derive(Component, Debug, Clone, Copy)]
pub struct Funds(pub u32);

#[derive(Component, Debug, Clone, Copy)]
pub struct HappinessLevel(pub f32);

#[derive(Component, Debug, Clone, Copy)]
pub struct Food(pub u32);

#[derive(Component, Debug, Clone, Copy)]
pub struct Works(pub u32);

#[derive(Debug, Clone, Copy)]
pub struct EconomyDelta {
    pub entity: Entity,
    pub population: i32,
    pub food: i32,
    pub funds: i32,
    pub happiness: f32,
}
