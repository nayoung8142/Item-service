package com.nayoung.itemservice.domain.item;

import com.nayoung.itemservice.domain.discount.DiscountCode;
import com.nayoung.itemservice.domain.item.log.ItemUpdateLog;
import com.nayoung.itemservice.domain.item.log.ItemUpdateLogRepository;
import com.nayoung.itemservice.domain.item.log.OrderStatus;
import com.nayoung.itemservice.domain.shop.Shop;
import com.nayoung.itemservice.domain.shop.ShopService;
import com.nayoung.itemservice.exception.ExceptionCode;
import com.nayoung.itemservice.exception.ItemException;
import com.nayoung.itemservice.exception.StockException;
import com.nayoung.itemservice.web.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItemService {

    private final ItemRepository itemRepository;
    private final ShopService shopService;
    private final ItemUpdateLogRepository itemUpdateLogRepository;

    public ItemDto create(ItemDto itemDto) {
        Shop shop = shopService.findShopById(itemDto.getShopId());
        Item item = Item.fromItemDtoAndShopEntity(itemDto, shop);
        itemRepository.save(item);

        Item savedItem = itemRepository.findByShopAndName(shop, item.getName())
                .orElseThrow(() -> new ItemException(ExceptionCode.NOT_FOUND_ITEM));

        return ItemDto.fromItem(savedItem);
    }

    public ItemDto findItemByItemId(Long itemId, String customerRating) {
        DiscountCode customerDiscountCode = DiscountCode.getDiscountCode(customerRating);
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ItemException(ExceptionCode.NOT_FOUND_ITEM));

        return DiscountCode.applyDiscountByItem(item, customerDiscountCode);
    }

    public List<ItemDto> findItemsAsync (String itemName, String location, String customerRating) {
        if(!StringUtils.hasText(location)) location = "none";
        List<Shop> shops = shopService.findAllShopByLocation(location);

        if(!StringUtils.hasText(customerRating)) customerRating = "UNQUALIFIE";
        DiscountCode customerDiscountCode = DiscountCode.getDiscountCode(customerRating);

        if (shops.isEmpty()) // 원하는 지역에 상점이 존재하지 않거나, 원하는 상점이 없는 경우
            return findItemsByNameAsync(itemName, customerDiscountCode);
        else // 원하는 지역에 상점이 존재하는 경우
            return findItemsByShopAsync(shops, itemName, customerDiscountCode);
    }

    /**
     * 원하는 지역에 상점이 존재하는 경우
     * 해당되는 상점에서 아이템을 찾아 할인 적용
     */
    private List<ItemDto> findItemsByShopAsync(List<Shop> shops, String itemName, DiscountCode discountCode) {
        List<CompletableFuture<ItemDto>> itemDtos = shops.stream()
                .map(shop -> CompletableFuture.supplyAsync(
                        () -> itemRepository.findByShopAndName(shop, itemName)))
                .map(future -> future.thenApply(ItemDto::getInstance))
                .map(future -> future.thenCompose(itemDto ->
                        CompletableFuture.supplyAsync(
                                () -> DiscountCode.applyDiscountByItemDto(itemDto, discountCode))))
                .collect(Collectors.toList());

        if(itemDtos.isEmpty()) return findItemsByNameAsync(itemName, discountCode);
        return itemDtos.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    /**
     * 원하는 지역에 상점이 존재하지 않거나, 원하는 상점이 없는 경우
     * 원하는 지역에 상점이 있지만, 상품이 없는 경우
     * 모든 지역을 기준으로 아이템을 가지고 있는 상점을 찾아 할인 적용
     */
    private List<ItemDto> findItemsByNameAsync(String itemName, DiscountCode discountCode) {
        List<Item> items = itemRepository.findAllByName(itemName);
        if(items.isEmpty())
            throw new ItemException(ExceptionCode.NOT_FOUND_ITEM);

        List<CompletableFuture<ItemDto>> itemResponses = items.stream()
                .map(item -> CompletableFuture.supplyAsync(
                        () -> DiscountCode.applyDiscountByItem(item, discountCode)))
                .collect(Collectors.toList());

        return itemResponses.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    @Transactional
    public ItemDto update(ItemInfoUpdateRequest itemInfoUpdateRequest) {
        Item item = itemRepository.findByIdWithPessimisticLock(itemInfoUpdateRequest.getItemId()).orElseThrow();
        item.update(itemInfoUpdateRequest);
        return ItemDto.fromItem(item);
    }

    public OrderItemResponse decreaseStockByRedisson(Long orderId, OrderItemRequest request) {
        boolean isSuccess = false;
        try {
            Item item = itemRepository.findById(request.getItemId())
                    .orElseThrow(() -> new ItemException(ExceptionCode.NOT_FOUND_ITEM));
            item.decreaseStock(request.getQuantity());
            itemRepository.save(item);

            isSuccess = true;
            ItemUpdateLog itemUpdateLog = ItemUpdateLog.from(OrderStatus.SUCCEED, orderId, request);
            itemUpdateLogRepository.save(itemUpdateLog);
        } catch (ItemException | StockException e) {
            isSuccess = false;
            ItemUpdateLog itemUpdateLog = ItemUpdateLog.from(OrderStatus.FAILED, orderId, request);
            itemUpdateLogRepository.save(itemUpdateLog);
        }
        if(isSuccess)
            return OrderItemResponse.fromOrderItemRequest(OrderStatus.SUCCEED, request);
        return OrderItemResponse.fromOrderItemRequest(OrderStatus.FAILED, request);
    }

    @Transactional
    public OrderItemResponse decreaseStockByPessimisticLock(Long orderId, OrderItemRequest request) {
        boolean isSuccess = false;
        try {
            Item item = itemRepository.findByIdWithPessimisticLock(request.getItemId())
                    .orElseThrow(() -> new ItemException(ExceptionCode.NOT_FOUND_ITEM));
            item.decreaseStock(request.getQuantity());

            isSuccess = true;
            ItemUpdateLog itemUpdateLog = ItemUpdateLog.from(OrderStatus.SUCCEED, orderId, request);
            itemUpdateLogRepository.save(itemUpdateLog);
        } catch (ItemException | StockException e) {
            isSuccess = false;
            ItemUpdateLog itemUpdateLog = ItemUpdateLog.from(OrderStatus.FAILED, orderId, request);
            itemUpdateLogRepository.save(itemUpdateLog);
        }
        if(isSuccess)
            return OrderItemResponse.fromOrderItemRequest(OrderStatus.SUCCEED, request);
        return OrderItemResponse.fromOrderItemRequest(OrderStatus.FAILED, request);
    }

    @Transactional
    public void undo(Long orderId, List<OrderItemResponse> orderItemResponses) {
        increaseStockByOrderId(orderId);
        for(OrderItemResponse orderItemResponse : orderItemResponses)
            orderItemResponse.setOrderStatus(OrderStatus.FAILED);
    }

    public void increaseStockByOrderId(Long orderId) {
        List<ItemUpdateLog> itemUpdateLogs = itemUpdateLogRepository.findAllByOrderId(orderId);
        for(ItemUpdateLog itemUpdateLog : itemUpdateLogs) {
            if(itemUpdateLog.getOrderStatus() == OrderStatus.SUCCEED) {
                try {
                    increaseStock(itemUpdateLog.getItemId(), itemUpdateLog.getQuantity());
                    itemUpdateLog.setOrderStatus(OrderStatus.CANCELED);
                } catch (ItemException e) {
                    log.error(e.getMessage());
                }
            }
        }
    }

    public void increaseStock(Long itemId, Long quantity) {
        Item item = itemRepository.findByIdWithPessimisticLock(itemId)
                .orElseThrow(() -> new ItemException(ExceptionCode.NOT_FOUND_ITEM));

        item.increaseStock(quantity);
    }
}