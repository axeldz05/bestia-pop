package com.bestiapop.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.ExperimentalFoundationApi
import com.bestiapop.android.data.model.Album
import com.bestiapop.android.data.model.Song
import com.bestiapop.android.ui.MusicPlayerViewModel
import com.bestiapop.android.ui.SortOption
import com.bestiapop.android.ui.components.SongListItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: MusicPlayerViewModel,
    onSelectFolderClick: () -> Unit,
    onSongSelect: (Song) -> Unit
) {
    val songs by viewModel.songsState.collectAsState()
    val albums by viewModel.albumsState.collectAsState()
    val artists by viewModel.artistsState.collectAsState()
    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
    val currentSong by viewModel.currentSong.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOption by viewModel.sortOption.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    // Tauon Layout option (show album section headers in song list, ON by default)
    var showAlbumHeaders by remember { mutableStateOf(true) }

    // Navigation states
    var selectedAlbumName by remember { mutableStateOf<String?>(null) }
    var selectedArtistName by remember { mutableStateOf<String?>(null) }

    // Multi-selection state for songs
    var selectedSongIds by remember { mutableStateOf(setOf<Long>()) }
    val isSongSelectionMode = selectedSongIds.isNotEmpty()

    // Multi-selection state for albums (when in Artist view or Albums tab)
    var selectedAlbumNames by remember { mutableStateOf(setOf<String>()) }
    val isAlbumSelectionMode = selectedAlbumNames.isNotEmpty()

    // Dialog states
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var songsForPlaylist by remember { mutableStateOf<List<Song>>(emptyList()) }

    val selectedSongs = remember(selectedSongIds, songs) {
        songs.filter { selectedSongIds.contains(it.id) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            // Header Row (Normal vs Song Multi-Selection Mode)
            if (isSongSelectionMode) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedSongIds = emptySet() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Descartar selección",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${selectedSongIds.size} canción(es)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        IconButton(
                            onClick = {
                                selectedSongIds = if (selectedSongIds.size == songs.size) {
                                    emptySet()
                                } else {
                                    songs.map { it.id }.toSet()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = "Seleccionar todo",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            } else if (isAlbumSelectionMode) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedAlbumNames = emptySet() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Descartar selección de álbumes",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${selectedAlbumNames.size} álbum(es)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Biblioteca",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Button(
                        onClick = onSelectFolderClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Importar",
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text("Importar")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar, Tauon Separator Toggle & Sort Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Buscar canción, artista...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Tauon Header Toggle Button
                IconButton(
                    onClick = { showAlbumHeaders = !showAlbumHeaders }
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewAgenda,
                        contentDescription = "Separadores de álbum",
                        tint = if (showAlbumHeaders) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Ordenar",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Separadores de Álbum (Tauon) ${if (showAlbumHeaders) "✓" else ""}") },
                            onClick = {
                                showAlbumHeaders = !showAlbumHeaders
                                sortMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Título ${if (sortOption == SortOption.TITLE) "✓" else ""}") },
                            onClick = {
                                viewModel.setSortOption(SortOption.TITLE)
                                sortMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Artista ${if (sortOption == SortOption.ARTIST) "✓" else ""}") },
                            onClick = {
                                viewModel.setSortOption(SortOption.ARTIST)
                                sortMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Álbum ${if (sortOption == SortOption.ALBUM) "✓" else ""}") },
                            onClick = {
                                viewModel.setSortOption(SortOption.ALBUM)
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tabs Header
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Canciones (${songs.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Álbumes (${albums.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("Artistas (${artists.size})") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tab Content
            when (selectedTabIndex) {
                0 -> {
                    if (songs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    modifier = Modifier.padding(16.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "No se encontraron canciones",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = if (isSongSelectionMode) 80.dp else 0.dp)
                        ) {
                            if (showAlbumHeaders) {
                                // Tauon Style: Group by Album with inline separators
                                val groupedByAlbum = songs.groupBy { it.album }
                                groupedByAlbum.forEach { (albumName, albumSongs) ->
                                    item(key = "album_header_$albumName") {
                                        TauonAlbumHeader(
                                            albumName = albumName,
                                            artistName = albumSongs.firstOrNull()?.artist ?: "Artista Desconocido",
                                            artworkUri = albumSongs.firstOrNull()?.artworkUri,
                                            songCount = albumSongs.size,
                                            onPlayAlbum = {
                                                viewModel.playCollection(albumSongs)
                                                albumSongs.firstOrNull()?.let { onSongSelect(it) }
                                            },
                                            onShuffleAlbum = {
                                                viewModel.shuffleCollection(albumSongs)
                                                albumSongs.firstOrNull()?.let { onSongSelect(it) }
                                            },
                                            onQueueAlbum = {
                                                viewModel.enqueueCollection(albumSongs)
                                            },
                                            onAddAlbumToPlaylist = {
                                                songsForPlaylist = albumSongs
                                                showPlaylistDialog = true
                                            }
                                        )
                                    }

                                    itemsIndexed(albumSongs, key = { idx, song -> "${song.id}_$idx" }) { _, song ->
                                        val isSelected = selectedSongIds.contains(song.id)
                                        SongListItem(
                                            song = song,
                                            isCurrentPlaying = currentSong?.uriString == song.uriString,
                                            isSelectionMode = isSongSelectionMode,
                                            isSelected = isSelected,
                                            onClick = {
                                                if (isSongSelectionMode) {
                                                    selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                                                } else {
                                                    viewModel.playSong(song, albumSongs)
                                                    onSongSelect(song)
                                                }
                                            },
                                            onLongClick = {
                                                if (!isSongSelectionMode) selectedSongIds = setOf(song.id)
                                            },
                                            onToggleSelect = {
                                                selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                                            },
                                            onPlayNext = { viewModel.playNextInQueue(song) },
                                            onAddToQueue = { viewModel.addToQueue(song) },
                                            onAddToPlaylist = {
                                                songsForPlaylist = listOf(song)
                                                showPlaylistDialog = true
                                            }
                                        )
                                    }
                                }
                            } else {
                                // Flat Song List
                                itemsIndexed(songs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                                    val isSelected = selectedSongIds.contains(song.id)
                                    SongListItem(
                                        song = song,
                                        isCurrentPlaying = currentSong?.uriString == song.uriString,
                                        isSelectionMode = isSongSelectionMode,
                                        isSelected = isSelected,
                                        onClick = {
                                            if (isSongSelectionMode) {
                                                selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                                            } else {
                                                viewModel.playSong(song, songs)
                                                onSongSelect(song)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSongSelectionMode) selectedSongIds = setOf(song.id)
                                        },
                                        onToggleSelect = {
                                            selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                                        },
                                        onPlayNext = { viewModel.playNextInQueue(song) },
                                        onAddToQueue = { viewModel.addToQueue(song) },
                                        onAddToPlaylist = {
                                            songsForPlaylist = listOf(song)
                                            showPlaylistDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Albums Tab
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(albums) { album ->
                            AlbumCard(
                                album = album,
                                onClick = { selectedAlbumName = album.name },
                                onPlayAlbum = {
                                    val albumSongs = songs.filter { it.album == album.name }
                                    if (albumSongs.isNotEmpty()) {
                                        viewModel.playSong(albumSongs.first(), albumSongs)
                                        onSongSelect(albumSongs.first())
                                    }
                                },
                                onQueueAlbum = {
                                    val albumSongs = songs.filter { it.album == album.name }
                                    viewModel.addToQueueBatch(albumSongs)
                                }
                            )
                        }
                    }
                }
                2 -> {
                    // Artists Tab
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(artists) { artist ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedArtistName = artist.name }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!artist.photoUri.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = artist.photoUri,
                                                contentDescription = artist.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = artist.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${artist.albumCount} álbumes • ${artist.songCount} canciones",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Artist Detail Screen (Shows Artist's Albums first + Multi-Album Selection)
        if (selectedArtistName != null) {
            val artistName = selectedArtistName!!
            val artistSongs = remember(artistName, songs) { songs.filter { it.artist == artistName } }
            val artistAlbums = remember(artistName, albums) { albums.filter { it.artist == artistName } }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            selectedArtistName = null
                            selectedAlbumNames = emptySet()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Volver",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artistName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Artista • ${artistAlbums.size} álbumes • ${artistSongs.size} canciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Play All Artist & Shuffle Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (artistSongs.isNotEmpty()) {
                                    viewModel.playSong(artistSongs.first(), artistSongs)
                                    onSongSelect(artistSongs.first())
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reproducir todo")
                        }

                        OutlinedButton(
                            onClick = {
                                if (artistSongs.isNotEmpty()) {
                                    val shuffled = artistSongs.shuffled()
                                    viewModel.playSong(shuffled.first(), shuffled)
                                    onSongSelect(shuffled.first())
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Shuffle, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Aleatorio")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // List of Artist Albums (Clicking album opens single album view!)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = if (isAlbumSelectionMode) 80.dp else 0.dp)
                    ) {
                        items(artistAlbums) { album ->
                            val isSelected = selectedAlbumNames.contains(album.name)
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .combinedClickable(
                                        onClick = {
                                            if (isAlbumSelectionMode) {
                                                selectedAlbumNames = if (isSelected) selectedAlbumNames - album.name else selectedAlbumNames + album.name
                                            } else {
                                                selectedAlbumName = album.name // Opens single album detail view!
                                            }
                                        },
                                        onLongClick = {
                                            selectedAlbumNames = if (isSelected) selectedAlbumNames - album.name else selectedAlbumNames + album.name
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isAlbumSelectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                selectedAlbumNames = if (isSelected) selectedAlbumNames - album.name else selectedAlbumNames + album.name
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!album.artworkUri.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = album.artworkUri,
                                                contentDescription = album.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Album,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = album.name,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${album.songCount} canciones",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }

                                    IconButton(onClick = {
                                        val albumSongs = songs.filter { it.album == album.name }
                                        if (albumSongs.isNotEmpty()) {
                                            viewModel.playSong(albumSongs.first(), albumSongs)
                                            onSongSelect(albumSongs.first())
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Reproducir disco",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Single Album Detail Screen Overlay
        if (selectedAlbumName != null) {
            val albumName = selectedAlbumName!!
            val albumSongs = remember(albumName, songs) { songs.filter { it.album == albumName } }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedAlbumName = null }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Volver",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = albumName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Álbum • ${albumSongs.firstOrNull()?.artist ?: ""} • ${albumSongs.size} canciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (albumSongs.isNotEmpty()) {
                                    viewModel.playSong(albumSongs.first(), albumSongs)
                                    onSongSelect(albumSongs.first())
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reproducir disco")
                        }

                        OutlinedButton(
                            onClick = {
                                if (albumSongs.isNotEmpty()) {
                                    val shuffled = albumSongs.shuffled()
                                    viewModel.playSong(shuffled.first(), shuffled)
                                    onSongSelect(shuffled.first())
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Shuffle, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Aleatorio")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = if (isSongSelectionMode) 80.dp else 0.dp)
                    ) {
                        itemsIndexed(albumSongs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                            val isSelected = selectedSongIds.contains(song.id)
                            SongListItem(
                                song = song,
                                isCurrentPlaying = currentSong?.uriString == song.uriString,
                                isSelectionMode = isSongSelectionMode,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSongSelectionMode) {
                                        selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                                    } else {
                                        viewModel.playSong(song, albumSongs)
                                        onSongSelect(song)
                                    }
                                },
                                onLongClick = {
                                    if (!isSongSelectionMode) selectedSongIds = setOf(song.id)
                                },
                                onToggleSelect = {
                                    selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                                },
                                onPlayNext = { viewModel.playNextInQueue(song) },
                                onAddToQueue = { viewModel.addToQueue(song) },
                                onAddToPlaylist = {
                                    songsForPlaylist = listOf(song)
                                    showPlaylistDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }

        // Multi-Album Bottom Action Bar
        if (isAlbumSelectionMode) {
            val selectedAlbumsSongs = remember(selectedAlbumNames, songs) {
                songs.filter { selectedAlbumNames.contains(it.album) }
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reproducir seleccionados
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            if (selectedAlbumsSongs.isNotEmpty()) {
                                viewModel.playCollection(selectedAlbumsSongs)
                                selectedAlbumsSongs.firstOrNull()?.let { onSongSelect(it) }
                            }
                            selectedAlbumNames = emptySet()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(text = "Reproducir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }

                    // Mezclar seleccionados
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            if (selectedAlbumsSongs.isNotEmpty()) {
                                viewModel.shuffleCollection(selectedAlbumsSongs)
                                selectedAlbumsSongs.firstOrNull()?.let { onSongSelect(it) }
                            }
                            selectedAlbumNames = emptySet()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(text = "Mezclar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }

                    // Añadir a la cola
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            viewModel.enqueueCollection(selectedAlbumsSongs)
                            selectedAlbumNames = emptySet()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(text = "A la cola", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }

                    // Playlist
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            songsForPlaylist = selectedAlbumsSongs
                            showPlaylistDialog = true
                        }
                    ) {
                        Icon(imageVector = Icons.Default.PlaylistAddCheck, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(text = "Playlist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }

        // Bottom Actions Bar for Song Multi-Selection
        if (isSongSelectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            viewModel.playNextBatch(selectedSongs)
                            selectedSongIds = emptySet()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(text = "A continuación", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            viewModel.addToQueueBatch(selectedSongs)
                            selectedSongIds = emptySet()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(text = "A la cola", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            songsForPlaylist = selectedSongs
                            showPlaylistDialog = true
                        }
                    ) {
                        Icon(imageVector = Icons.Default.PlaylistAddCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(text = "Playlist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            showDeleteDialog = true
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(text = "Eliminar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog) {
        val targets = if (isSongSelectionMode) selectedSongs else emptyList()
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = "¿Eliminar ${targets.size} canción(es)?", fontWeight = FontWeight.Bold) },
            text = { Text(text = "Elegí cómo querés eliminar las canciones seleccionadas:") },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.deleteSongsFromApp(targets)
                            showDeleteDialog = false
                            selectedSongIds = emptySet()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Solo quitar de la app")
                    }

                    Button(
                        onClick = {
                            viewModel.deleteSongsFromDevice(targets)
                            showDeleteDialog = false
                            selectedSongIds = emptySet()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Eliminar del dispositivo (Disco)")
                    }

                    OutlinedButton(
                        onClick = { showDeleteDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancelar")
                    }
                }
            },
            dismissButton = null
        )
    }

    // Add to Playlist Dialog
    if (showPlaylistDialog) {
        val targets = songsForPlaylist
        AlertDialog(
            onDismissRequest = {
                showPlaylistDialog = false
                songsForPlaylist = emptyList()
            },
            title = { Text("Añadir a Playlist (${targets.size} canción/es)") },
            text = {
                Column {
                    if (playlists.isEmpty()) {
                        Text(
                            text = "No tenés playlists creadas aún.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            items(playlists) { playlist ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            viewModel.addSongsToPlaylist(playlist.id, targets)
                                            showPlaylistDialog = false
                                            songsForPlaylist = emptyList()
                                            selectedSongIds = emptySet()
                                            selectedAlbumNames = emptySet()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(imageVector = Icons.Default.PlaylistAddCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(text = playlist.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = { showCreatePlaylistDialog = true }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Crear nueva playlist")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showPlaylistDialog = false
                    songsForPlaylist = emptyList()
                }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Create New Playlist Dialog
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Nueva Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Nombre de la playlist") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylist(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                        }
                    }
                ) {
                    Text("Crear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun TauonAlbumHeader(
    albumName: String,
    artistName: String,
    artworkUri: String?,
    songCount: Int,
    onPlayAlbum: () -> Unit,
    onShuffleAlbum: () -> Unit,
    onQueueAlbum: () -> Unit,
    onAddAlbumToPlaylist: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!artworkUri.isNullOrEmpty()) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = albumName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = albumName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$artistName • $songCount canciones",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onPlayAlbum, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Reproducir disco",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onShuffleAlbum, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Aleatorio",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(onClick = onQueueAlbum, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Añadir disco a la cola",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Opciones",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Añadir disco a Playlist") },
                        onClick = {
                            menuExpanded = false
                            onAddAlbumToPlaylist()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onPlayAlbum: () -> Unit,
    onQueueAlbum: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!album.artworkUri.isNullOrEmpty()) {
                    AsyncImage(
                        model = album.artworkUri,
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.artist} • ${album.songCount} canciones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onPlayAlbum) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Reproducir disco",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onQueueAlbum) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "Añadir disco a la cola",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}
