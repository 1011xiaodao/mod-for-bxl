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
struct ColonySnapshot {
    id:i8,
    population:u32,
    funds:i32,
    happiness_level:f32,
    food:u32
}
struct EconomyDelta {
    population_delta: i32,
    funds_delta: i32,
    happiness_delta: f32,
    food_delta:i32
}