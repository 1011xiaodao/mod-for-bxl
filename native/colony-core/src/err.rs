use thiserror::Error;


#[derive(Debug, Error)]

pub enum Error {
    #[error("输入数据非法 {0}")]
    InvalidArgument(String),
    #[error("rust内部计算异常 {0}")]
    InternalError(String),
    #[error("功能还没有实现{0}")]
    NotImplemented(String),
}