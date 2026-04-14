package nl.rijksoverheid.moz.berichtenmagazijn.opslag

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class BerichtRepository : PanacheRepositoryBase<BerichtEntity, UUID>
