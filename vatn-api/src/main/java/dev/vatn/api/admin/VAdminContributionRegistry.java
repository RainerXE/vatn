package dev.vatn.api.admin;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;
import java.util.List;

/**
 * A registry for {@link VAdminContribution} entries contributed by plugins.
 * Plugins register their nav contributions at init time so the admin dashboard
 * can render navigation links to each plugin's UI.
 */
@VatnApi(since = "1.0-alpha.15")
public interface VAdminContributionRegistry extends VService {

    /**
     * Registers a new admin UI contribution.
     *
     * @param contribution the contribution to register
     */
    void register(VAdminContribution contribution);

    /**
     * Returns all registered contributions.
     *
     * @return an immutable snapshot of registered contributions
     */
    List<VAdminContribution> getContributions();
}
