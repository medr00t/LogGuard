import jenkins.model.*
import hudson.security.*
import jenkins.security.s2m.AdminWhitelistRule

def instance = Jenkins.get()

// ── Admin user (same credentials as LogGuard) ────────────────────
if (!(instance.getSecurityRealm() instanceof HudsonPrivateSecurityRealm)) {
    def realm = new HudsonPrivateSecurityRealm(false)
    realm.createAccount('admin', 'admin123')
    instance.setSecurityRealm(realm)

    def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
    strategy.setAllowAnonymousRead(false)
    instance.setAuthorizationStrategy(strategy)
    println '[LogGuard] Security realm configured: admin / admin123'
}

// ── Disable CSRF so the LogGuard backend can call the REST API ───
instance.setCrumbIssuer(null)
println '[LogGuard] CSRF protection disabled (dev mode)'

// ── Agent-to-master security ─────────────────────────────────────
instance.getInjector()
    .getInstance(AdminWhitelistRule.class)
    .setMasterKillSwitch(false)

instance.save()
println '[LogGuard] Jenkins setup complete'
