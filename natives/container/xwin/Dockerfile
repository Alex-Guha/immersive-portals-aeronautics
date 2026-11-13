# Build container versions pinned on 04-18-26
FROM messense/cargo-xwin@sha256:f3e680c963eff35f77908c78d97ac095d4bef1f69e4fe200447576b34cd251cb

ARG RUST_VERSION
# Windows SDK pinned (2026-11-10): with the latest in Microsoft's manifest (10.0.26100) this xwin fails with "unable to
# find x86_64 headers for Win11SDK_10.0.26100".
ENV XWIN_SDK_VERSION=10.0.22621
RUN rustup default ${RUST_VERSION}
RUN rustup target add x86_64-pc-windows-msvc
RUN rustup target add aarch64-pc-windows-msvc
RUN cargo xwin cache xwin

WORKDIR /
