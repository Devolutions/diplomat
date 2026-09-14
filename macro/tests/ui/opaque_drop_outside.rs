#[diplomat::bridge]
mod ffi {
    #[diplomat::opaque]
    pub struct Foo;
}

impl Drop for ffi::Foo {
    fn drop(&mut self) {}
}

fn main() {}