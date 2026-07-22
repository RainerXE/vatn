package dev.vatn.api.admin;

import dev.vatn.api.VService;
import dev.vatn.api.VatnApi;
import java.util.List;

@VatnApi(since = "1.0-alpha.15")
public interface VAdminContributionRegistry extends VService {

    void register(VAdminContribution contribution);

    List<VAdminContribution> getContributions();
}
