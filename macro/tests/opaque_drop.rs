#[test]
fn opaque_drop_is_rejected_inside_and_outside_bridge() {
    let tests = trybuild::TestCases::new();
    tests.compile_fail("tests/ui/opaque_drop_inside.rs");
    tests.compile_fail("tests/ui/opaque_drop_outside.rs");
}
