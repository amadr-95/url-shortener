package com.amador.urlshortener.services;

import com.amador.urlshortener.config.AppProperties;
import com.amador.urlshortener.domain.entities.PagedResult;
import com.amador.urlshortener.domain.entities.ShortUrl;
import com.amador.urlshortener.domain.entities.User;
import com.amador.urlshortener.domain.entities.dto.ShortUrlDTO;
import com.amador.urlshortener.exceptions.UrlException;
import com.amador.urlshortener.exceptions.UrlExpiredException;
import com.amador.urlshortener.exceptions.UrlNotFoundException;
import com.amador.urlshortener.repositories.ShortUrlRepository;
import com.amador.urlshortener.security.AuthenticationService;
import com.amador.urlshortener.services.mapper.ShortUrlMapper;
import com.amador.urlshortener.web.controllers.dto.ShortUrlForm;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShortUrlService {

    private final ShortUrlRepository shortUrlRepository;
    private final ShortUrlMapper shortUrlMapper;
    private final AuthenticationService authenticationService;
    private final AppProperties appProperties;
    private final PaginationService paginationService;

    public PagedResult<ShortUrlDTO> findAllPublicUrls(Pageable pageableRequest) {
        Pageable validPage = paginationService.createValidPage(pageableRequest, shortUrlRepository::countAllPublicUrls);

        //We need some wrapper object that allows us to store all the data coming from Page so we can show it in the frontend
        return PagedResult.from(shortUrlRepository.findAllPublicUrls(validPage)
                .map(shortUrlMapper::toShortUrlDTO));
    }

    @Transactional
    public ShortUrlDTO createShortUrl(ShortUrlForm shortUrlForm) throws UrlException {
        User currentUser = authenticationService.getCurrentUserInfo();
        Integer expirationInDays;
        LocalDateTime createdAt = LocalDateTime.now();

        if (currentUser == null)
            expirationInDays = appProperties.shortUrlProperties().defaultExpiryDays();
        else if (shortUrlForm.expirationInDays() == null) expirationInDays = null;
        else expirationInDays = shortUrlForm.expirationInDays();

        ShortUrl shortUrl = ShortUrl.builder()
                .originalUrl(shortUrlForm.originalUrl())
                .shortenedUrl(shortenUrl(shortUrlForm.urlLength()))
                .createdByUser(currentUser) //either null or real user
                .isPrivate(shortUrlForm.isPrivate() != null && shortUrlForm.isPrivate()) //false by default if the user is not logged in
                .numberOfClicks(0L)
                .createdAt(createdAt)
                .expiresAt(expirationInDays == null ?
                        null :
                        createdAt.plusDays(expirationInDays)) //either the default value or the custom value picked by the authenticated user
                .build();

        shortUrlRepository.save(shortUrl);
        return shortUrlMapper.toShortUrlDTO(shortUrl);
    }

    private String shortenUrl(Integer length) throws UrlException {
        Random random = new Random();
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        int shortUrlLength = length == null ?
                appProperties.shortUrlProperties().urlLength() : length;
        int maxAttempts = 5;
        StringBuilder shortUrl = new StringBuilder(shortUrlLength);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            for (int i = 0; i < shortUrlLength; i++) {
                shortUrl.append(characters.charAt(random.nextInt(characters.length())));
            }

            //check if that combination of characters already exists in database
            if (!shortUrlRepository.existsByShortenedUrl(shortUrl.toString()))
                return shortUrl.toString();
        }
        throw new UrlException("URL cannot be created because it exceeds the number of allowed attempts");
    }

    @Transactional
    public String accessOriginalUrl(String url) throws UrlException {
        if (url == null || url.isBlank()) throw new UrlException("URL is null or empty");
        ShortUrl shortUrl = shortUrlRepository.findByShortenedUrl(url)
                .orElseThrow(() -> new UrlNotFoundException(String.format("URL '%s' not found", url)));

        LocalDateTime expiresAt = shortUrl.getExpiresAt();
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now()))
            throw new UrlExpiredException(String.format("URL '%s' is expired", url));

        validateUserPermissions(authenticationService.getCurrentUserInfo(), shortUrl);

        shortUrl.setNumberOfClicks(shortUrl.getNumberOfClicks() + 1);
        shortUrlRepository.save(shortUrl);
        return shortUrl.getOriginalUrl();
    }

    private void validateUserPermissions(User currentUser, ShortUrl shortUrl) throws UrlException {
        if (!shortUrl.isPrivate()) {
            log.debug("Accessing public url '{}'", shortUrl.getShortenedUrl());
            return; //public urls are accessible by all
        }
        if (currentUser == null) {
            log.warn("Accessing private url '{}' without logging in", shortUrl.getShortenedUrl());
            throw new UrlException("Trying to access to private URL without logging in");
        }
        if (!shortUrl.getCreatedByUser().getId().equals(currentUser.getId())) {
            log.warn("Accessing private url '{}' with wrong user", shortUrl.getShortenedUrl());
            throw new UrlException("Trying to access to private URL with wrong user");
        }
    }
}
