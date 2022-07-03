package exh.favorites

import eu.kanade.data.DatabaseHandler
import eu.kanade.data.exh.favoriteEntryMapper
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.domain.manga.interactor.GetFavorites
import eu.kanade.domain.manga.model.Manga
import eu.kanade.tachiyomi.data.database.models.toDomainManga
import eu.kanade.tachiyomi.source.online.all.EHentai
import exh.favorites.sql.models.FavoriteEntry
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.source.isEhBasedManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import uy.kohesive.injekt.injectLazy

class LocalFavoritesStorage {
    private val handler: DatabaseHandler by injectLazy()
    private val getFavorites: GetFavorites by injectLazy()
    private val getCategories: GetCategories by injectLazy()

    suspend fun getChangedDbEntries() = getFavorites.await()
        .asFlow()
        .loadDbCategories()
        .parseToFavoriteEntries()
        .getChangedEntries()

    suspend fun getChangedRemoteEntries(entries: List<EHentai.ParsedManga>) = entries
        .asFlow()
        .map {
            it.fav to it.manga.apply {
                id = -1
                favorite = true
                date_added = System.currentTimeMillis()
            }.toDomainManga()!!
        }
        .parseToFavoriteEntries()
        .getChangedEntries()

    suspend fun snapshotEntries() {
        val dbMangas = getFavorites.await()
            .asFlow()
            .loadDbCategories()
            .parseToFavoriteEntries()

        // Delete old snapshot
        handler.await { eh_favoritesQueries.deleteAll() }

        // Insert new snapshots
        handler.await(true) {
            dbMangas.toList().forEach {
                eh_favoritesQueries.insertEhFavorites(
                    it.id,
                    it.title,
                    it.gid,
                    it.token,
                    it.category.toLong(),
                )
            }
        }
    }

    suspend fun clearSnapshots() {
        handler.await { eh_favoritesQueries.deleteAll() }
    }

    private suspend fun Flow<FavoriteEntry>.getChangedEntries(): ChangeSet {
        val terminated = toList()

        val databaseEntries = handler.awaitList { eh_favoritesQueries.selectAll(favoriteEntryMapper) }

        val added = terminated.filter {
            queryListForEntry(databaseEntries, it) == null
        }

        val removed = databaseEntries
            .filter {
                queryListForEntry(terminated, it) == null
            } /*.map {
                todo see what this does
                realm.copyFromRealm(it)
            }*/

        return ChangeSet(added, removed)
    }

    private fun queryListForEntry(list: List<FavoriteEntry>, entry: FavoriteEntry) =
        list.find {
            it.gid == entry.gid &&
                it.token == entry.token &&
                it.category == entry.category
        }

    private suspend fun Flow<Manga>.loadDbCategories(): Flow<Pair<Int, Manga>> {
        val dbCategories = getCategories.await()

        return filter(::validateDbManga).mapNotNull {
            val category = getCategories.await(it.id)

            dbCategories.indexOf(
                category.firstOrNull()
                    ?: return@mapNotNull null,
            ) to it
        }
    }

    private fun Flow<Pair<Int, Manga>>.parseToFavoriteEntries() =
        filter { (_, manga) ->
            validateDbManga(manga)
        }.mapNotNull { (categoryId, manga) ->
            FavoriteEntry(
                title = manga.ogTitle,
                gid = EHentaiSearchMetadata.galleryId(manga.url),
                token = EHentaiSearchMetadata.galleryToken(manga.url),
                category = categoryId,
            ).also {
                if (it.category > MAX_CATEGORIES) {
                    return@mapNotNull null
                }
            }
        }

    private fun validateDbManga(manga: Manga) =
        manga.favorite && manga.isEhBasedManga()

    companion object {
        const val MAX_CATEGORIES = 9
    }
}

data class ChangeSet(
    val added: List<FavoriteEntry>,
    val removed: List<FavoriteEntry>,
)
